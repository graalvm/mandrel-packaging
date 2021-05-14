.PHONY: default reuse-image boot-fedora-image setup-image stop-image run-image-attach run-image rm-image cp-script

DOCKER ?= docker
IMAGE_NAME ?= mandrel-packaging
BOOT_CONTAINER ?= $(IMAGE_NAME)-boot
PLAYBOOK ?= playbook.yml
PLAYBOOK_CONF ?= default-openjdk
DOCKER_RUN_OPTIONS ?=
AT ?= @
$(AT)VERBOSE = -v

default:
	make build-image
	make run-image

build-image:
	$(AT)$(DOCKER) stop $(BOOT_CONTAINER) 2>&1 > /dev/null || true
	$(AT)$(DOCKER) rm $(BOOT_CONTAINER) 2>&1 > /dev/null || true
	$(AT)$(DOCKER) run --name=$(BOOT_CONTAINER) -itd fedora:32
	$(AT)ansible-playbook $(VERBOSE) -i $(BOOT_CONTAINER), -c $(DOCKER) $(PLAYBOOK) -e configuration=$(PLAYBOOK_CONF)
	$(AT)$(DOCKER) commit --author "Mandrel Packaging" $(BOOT_CONTAINER) $(IMAGE_NAME)
	$(AT)$(DOCKER) stop $(BOOT_CONTAINER)
	$(AT)$(DOCKER) rm $(BOOT_CONTAINER)

run-image:
	$(AT)$(DOCKER) run -it $(DOCKER_RUN_OPTIONS) -w /root --rm $(IMAGE_NAME) || echo -e "\n\nPlease run make build-image first"

refresh-image:
	$(AT)$(DOCKER) run --name=$(BOOT_CONTAINER) -itd $(IMAGE_NAME)
	$(AT)ansible-playbook -i $(BOOT_CONTAINER), -c $(DOCKER) $(PLAYBOOK) -e configuration=$(PLAYBOOK_CONF)
	$(AT)$(DOCKER) commit --author "Mandrel Packaging" $(BOOT_CONTAINER) $(IMAGE_NAME)
	$(AT)$(DOCKER) stop $(BOOT_CONTAINER)
	$(AT)$(DOCKER) rm $(BOOT_CONTAINER)
