VERSION=0.7.5
MESOS_VERSION=0.20.1
REGISTRY=docker-dev.yelpcorp.com

build:
	docker build -t $(REGISTRY)/marathon:$(VERSION)-mesos-$(MESOS_VERSION) .

push:
	docker push $(REGISTRY)/marathon:$(VERSION)-mesos-$(MESOS_VERSION)


