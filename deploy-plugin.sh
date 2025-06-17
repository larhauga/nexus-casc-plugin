#!/bin/sh

# Script to deploy nexus-casc-plugin KAR file
# This script copies the plugin bundle to the Nexus deployment directory
# Usage: ./deploy-plugin.sh [SOURCE_PATH] [DEST_DIR]
#   SOURCE_PATH: Path to the source KAR file (default: /root/nexus-casc-plugin/target/nexus-casc-plugin-bundle.kar)
#   DEST_DIR: Destination directory (default: /opt/sonatype/nexus/deploy)

set -e

# Set default values
DEFAULT_SOURCE_PATH="/home/nexus/nexus-casc-plugin-bundle.kar"
DEFAULT_DEST_DIR="/opt/sonatype/nexus/deploy"

# Use command line arguments or defaults
SOURCE_PATH="${1:-$DEFAULT_SOURCE_PATH}"
DEST_DIR="${2:-$DEFAULT_DEST_DIR}"
DEST_PATH="${DEST_DIR}/nexus-casc-plugin.kar"

# Display help if requested
if [[ "$1" == "-h" || "$1" == "--help" ]]; then
    echo "Usage: $0 [SOURCE_PATH] [DEST_DIR]"
    echo ""
    echo "Deploy nexus-casc-plugin KAR file to Nexus deployment directory"
    echo ""
    echo "Arguments:"
    echo "  SOURCE_PATH   Path to the source KAR file"
    echo "                (default: $DEFAULT_SOURCE_PATH)"
    echo "  DEST_DIR      Destination directory"
    echo "                (default: $DEFAULT_DEST_DIR)"
    echo ""
    echo "Examples:"
    echo "  $0                                    # Use default paths"
    echo "  $0 ./my-plugin.kar                   # Custom source, default dest"
    echo "  $0 ./my-plugin.kar /custom/deploy    # Custom source and dest"
    exit 0
fi

echo "Deploying Nexus CASC Plugin..."
echo "Source: ${SOURCE_PATH}"
echo "Destination: ${DEST_PATH}"

# Create destination directory if it doesn't exist
if [ ! -d "${DEST_DIR}" ]; then
    echo "Creating deployment directory: ${DEST_DIR}"
    mkdir -p "${DEST_DIR}"
fi

# Check if source file exists
if [ ! -f "${SOURCE_PATH}" ]; then
    echo "Error: Source file not found: ${SOURCE_PATH}"
    exit 1
fi

# Copy the KAR file
echo "Copying ${SOURCE_PATH} to ${DEST_PATH}"
cat "${SOURCE_PATH}" > "${DEST_PATH}"

# Verify the copy was successful
if [ -f "${DEST_PATH}" ]; then
    echo "Plugin deployed successfully to ${DEST_PATH}"
    ls -la "${DEST_PATH}"
else
    echo "Error: Failed to deploy plugin"
    exit 1
fi

echo "Deployment completed successfully!"
