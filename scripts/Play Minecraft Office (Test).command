#!/bin/bash
# Double-click to set up and launch the Minecraft Office test instance.
exec "$(cd "$(dirname "$(readlink "$0" || echo "$0")")" && pwd)/play-test.sh"
