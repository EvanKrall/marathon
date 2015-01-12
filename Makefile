VERSION=0.7.6
MESOS_VERSION=0.20.1

REGISTRY=docker-paasta.yelpcorp.com:443
REGISTRY2=docker-dev.yelpcorp.com

build:
	docker build -t $(REGISTRY)/marathon:$(VERSION)-mesos-$(MESOS_VERSION) .
	docker build -t $(REGISTRY2)/marathon:$(VERSION)-mesos-$(MESOS_VERSION) .

push:
	sudo -H docker push $(REGISTRY)/marathon:$(VERSION)-mesos-$(MESOS_VERSION)
	        docker push $(REGISTRY2)/marathon:$(VERSION)-mesos-$(MESOS_VERSION)


