#!/bin/bash
# scripts/termux/install.sh
# Native XFCE4 installation for Termux

# Enable strict error handling
set -e

echo "Altaris: Installing XFCE4 for Termux Native..."

# Update packages
echo "Altaris: Updating packages..."
pkg update -y

# Install XFCE4 and essential tools
echo "Altaris: Installing XFCE4..."
pkg install -y xfce4 xfce4-terminal tigervnc

echo "Altaris: XFCE4 Installed Successfully."
