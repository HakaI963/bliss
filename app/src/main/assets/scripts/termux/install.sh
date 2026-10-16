#!/bin/bash
# scripts/termux/install.sh
# Native XFCE4 installation for Termux

# Enable strict error handling
set -e

echo "Bliss: Installing XFCE4 for Termux Native..."

# Update packages
echo "Bliss: Updating packages..."
pkg update -y

# Install XFCE4 and essential tools
echo "Bliss: Installing XFCE4..."
pkg install -y xfce4 xfce4-terminal tigervnc

echo "Bliss: XFCE4 Installed Successfully."
