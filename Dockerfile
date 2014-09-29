FROM docker-dev.yelpcorp.com/trusty_yelp:latest

RUN echo "deb http://repos.mesosphere.io/ubuntu/ trusty main" > /etc/apt/sources.list.d/mesosphere.list && \
    apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF && \
    apt-get update

RUN apt-get install -y \
    default-jdk \
    mesos=0.19.1-1.0.ubuntu1404 \
    scala \
    curl 

RUN curl -SsL -O http://dl.bintray.com/sbt/debian/sbt-0.13.5.deb && \
    dpkg -i sbt-0.13.5.deb

COPY . /marathon
WORKDIR /marathon

RUN sbt assembly

ENTRYPOINT ["./bin/start"]
