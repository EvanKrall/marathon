package mesosphere.mesos

import scala.collection.JavaConverters._
import scala.collection.mutable

import java.io.ByteArrayOutputStream

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.ByteString
import org.apache.log4j.Logger
import org.apache.mesos.Protos.Environment._
import org.apache.mesos.Protos._

import mesosphere.marathon._
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.tasks.{ PortsMatcher, TaskTracker }
import mesosphere.marathon.Protos.Constraint
import mesosphere.mesos.protos.{ RangesResource, Resource, ScalarResource }

import scala.collection.immutable.Seq
import scala.util.{ Failure, Success, Try }

class TaskBuilder(app: AppDefinition,
                  newTaskId: PathId => TaskID,
                  taskTracker: TaskTracker,
                  config: MarathonConf,
                  mapper: ObjectMapper = new ObjectMapper()) {

  import mesosphere.mesos.protos.Implicits._

  val log = Logger.getLogger(getClass.getName)

  def buildIfMatches(offer: Offer): Option[(TaskInfo, Seq[Long])] = {
    var cpuRole = ""
    var memRole = ""
    var diskRole = ""
    var portsResource: RangesResource = null

    offerMatches(offer) match {
      case Some((cpu, mem, disk, ranges)) =>
        cpuRole = cpu
        memRole = mem
        diskRole = disk
        portsResource = ranges
      case _ =>
        log.info(s"No matching offer for ${app.id} (need cpus=${app.cpus}, mem=${app.mem}, disk=${app.disk}, ports=${app.requestedPorts}) : " + offer)
        return None
    }

    val executor: Executor = if (app.executor == "") {
      Main.conf.executor
    }
    else {
      Executor.dispatch(app.executor)
    }

    val ports = portsResource.ranges.flatMap(_.asScala()).to[Seq]

    val taskId = newTaskId(app.id)
    val builder = TaskInfo.newBuilder
      .setName(taskId.getValue)
      .setTaskId(taskId)
      .setSlaveId(offer.getSlaveId)
      .addResources(ScalarResource(Resource.CPUS, app.cpus, cpuRole))
      .addResources(ScalarResource(Resource.MEM, app.mem, memRole))

    if (portsResource.ranges.nonEmpty) {
      builder.addResources(portsResource)
    }

    val containerProto: Option[ContainerInfo] =
      app.container.map { c =>
        val portMappings = c.docker.map { d =>
          d.portMappings zip ports map {
            case (mapping, port) => mapping.copy(hostPort = port.toInt)
          }
        }
        val containerWithPortMappings = portMappings match {
          case None => c
          case Some(newMappings) => c.copy(
            docker = c.docker.map { _.copy(portMappings = newMappings) }
          )
        }
        containerWithPortMappings.toProto
      }

    executor match {
      case CommandExecutor() =>
        builder.setCommand(TaskBuilder.commandInfo(app, ports))
        for (c <- containerProto) builder.setContainer(c)

      case PathExecutor(path) =>
        val executorId = f"marathon-${taskId.getValue}" // Fresh executor
        val executorPath = s"'$path'" // TODO: Really escape this.
        val cmd = app.cmd orElse app.args.map(_ mkString " ") getOrElse ""
        val shell = s"chmod ug+rx $executorPath && exec $executorPath $cmd"
        val command =
          TaskBuilder.commandInfo(app, ports).toBuilder.setValue(shell)

        val info = ExecutorInfo.newBuilder()
          .setExecutorId(ExecutorID.newBuilder().setValue(executorId))
          .setCommand(command)
        for (c <- containerProto) info.setContainer(c)
        builder.setExecutor(info)
        val binary = new ByteArrayOutputStream()
        mapper.writeValue(binary, app)
        builder.setData(ByteString.copyFrom(binary.toByteArray))
    }

    if (config.executorHealthChecks()) {
      import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol

      // Mesos supports at most one health check, and only COMMAND checks
      // are currently implemented.
      val mesosHealthCheck: Option[org.apache.mesos.Protos.HealthCheck] =
        app.healthChecks.collectFirst {
          case healthCheck if healthCheck.protocol == Protocol.COMMAND =>
            Try(healthCheck.toMesos(ports.map(_.toInt))) match {
              case Success(mhc) => Some(mhc)
              case Failure(cause) =>
                log.warn(
                  s"An error occurred with health check [$healthCheck]\n" +
                    s"Error: [${cause.getMessage}]")
                None
            }
        }.flatten

      mesosHealthCheck foreach builder.setHealthCheck

      if (mesosHealthCheck.size < app.healthChecks.size) {
        val numUnusedChecks = app.healthChecks.size - mesosHealthCheck.size
        log.warn(
          "Mesos supports one command health check per task.\n" +
            s"Task [$taskId] will run without " +
            s"$numUnusedChecks of its defined health checks."
        )
      }
    }

    Some(builder.build -> ports)
  }

  private def offerMatches(offer: Offer): Option[(String, String, String, RangesResource)] = {
    var cpuRole = ""
    var memRole = ""
    var diskRole = ""
    val portMatcher = new PortsMatcher(app, offer)

    for (resource <- offer.getResourcesList.asScala) {
      if (cpuRole.isEmpty &&
        resource.getName == Resource.CPUS &&
        resource.getScalar.getValue >= app.cpus) {
        cpuRole = resource.getRole
      }
      if (memRole.isEmpty &&
        resource.getName == Resource.MEM &&
        resource.getScalar.getValue >= app.mem) {
        memRole = resource.getRole
      }
      if (diskRole.isEmpty &&
        resource.getName == Resource.DISK &&
        resource.getScalar.getValue >= app.disk) {
        diskRole = resource.getRole
      }
    }

    if (cpuRole.isEmpty || memRole.isEmpty || diskRole.isEmpty) {
      return None
    }

    val badConstraints: Set[Constraint] = {
      val runningTasks = taskTracker.get(app.id)
      app.constraints.filterNot { constraint =>
        Constraints.meetsConstraint(runningTasks, offer, constraint)
      }
    }

    portMatcher.portRanges match {
      case None =>
        log.warn("App ports are not available in the offer.")
        None

      case _ if badConstraints.nonEmpty =>
        log.warn(
          s"Offer did not satisfy constraints for app [${app.id}].\n" +
            s"Conflicting constraints are: [${badConstraints.mkString(", ")}]"
        )
        None

      case Some(portRanges) =>
        log.info("Met all constraints.")
        Some((cpuRole, memRole, diskRole, portRanges))
    }
  }

}

object TaskBuilder {

  def commandInfo(app: AppDefinition, ports: Seq[Long]) = {
    val envMap = app.env ++ portsEnv(ports)

    val builder = CommandInfo.newBuilder()
      .setEnvironment(environment(envMap))

    app.cmd match {
      case Some(cmd) if cmd.nonEmpty =>
        builder.setValue(cmd)
      case _ =>
        builder.setShell(false)
    }

    // args take precedence over command, if supplied
    app.args.foreach { argv =>
      builder.setShell(false)
      builder.addAllArguments(argv.asJava)
    }

    if (app.uris != null) {
      val uriProtos = app.uris.map(uri => {
        CommandInfo.URI.newBuilder()
          .setValue(uri)
          .setExtract(isExtract(uri))
          .build()
      })
      builder.addAllUris(uriProtos.asJava)
    }

    app.user.foreach(builder.setUser)

    builder.build
  }

  private def isExtract(stringuri: String): Boolean = {
    stringuri.endsWith(".tgz") ||
      stringuri.endsWith(".tar.gz") ||
      stringuri.endsWith(".tbz2") ||
      stringuri.endsWith(".tar.bz2") ||
      stringuri.endsWith(".txz") ||
      stringuri.endsWith(".tar.xz") ||
      stringuri.endsWith(".zip")
  }

  def environment(vars: Map[String, String]) = {
    val builder = Environment.newBuilder()

    for ((key, value) <- vars) {
      val variable = Variable.newBuilder().setName(key).setValue(value)
      builder.addVariables(variable)
    }

    builder.build()
  }

  def portsEnv(ports: Seq[Long]): scala.collection.Map[String, String] = {
    if (ports.isEmpty) {
      return Map.empty
    }

    val env = mutable.HashMap.empty[String, String]

    ports.zipWithIndex.foreach(p => {
      env += (s"PORT${p._2}" -> p._1.toString)
    })

    env += ("PORT" -> ports.head.toString)
    env += ("PORTS" -> ports.mkString(","))
    env
  }
}
