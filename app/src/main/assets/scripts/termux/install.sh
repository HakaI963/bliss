#!/bin/bash
# scripts/termux/install.sh
# Native XFCE4 installation for Termux

# Enable strict error handling
set -e

echo "Folio: Installing XFCE4 for Termux Native..."

# Update packages
echo "Folio: Updating packages..."
pkg update -y

# Install XFCE4 and essential tools
echo "Folio: Installing XFCE4..."
pkg install -y xfce4 xfce4-terminal tigervnc

echo "Folio: XFCE4 Installed Successfully."
