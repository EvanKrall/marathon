VERSION=0.7.5
MESOS_VERSION=0.20.1
#REGISTRY=docker-dev.yelpcorp.com

REGISTRY=docker-paasta.yelpcorp.com:443

build:
	docker build -t $(REGISTRY)/marathon:$(VERSION)-mesos-$(MESOS_VERSION) .

push:
	sudo docker push $(REGISTRY)/marathon:$(VERSION)-mesos-$(MESOS_VERSION)


