#!/bin/bash

set -e -x

useradd -ms /bin/bash mandrel
usermod mandrel -a -G wheel
echo '%wheel ALL=(ALL) NOPASSWD:ALL' >> /etc/sudoers
