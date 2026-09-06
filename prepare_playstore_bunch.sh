#!/bin/bash
echo "Preparing Google Play Store release bunch..."

mkdir -p playstore_release
rm -rf playstore_release/*

# Copy the generated App Bundle
cp app/build/outputs/bundle/release/app-release.aab playstore_release/harnessDroid-release.aab

# Copy the Keystore so the user can keep it
cp release.keystore playstore_release/release.keystore

# Create a read-me file with store passwords
cat << 'README' > playstore_release/KEYSTORE_CREDENTIALS.txt
Keystore Details for harnessDroid

Store File: release.keystore
Store Password: password123
Key Alias: key0
Key Password: password123

DO NOT LOSE THIS FILE OR THE KEYSTORE. Google Play requires this exact keystore to sign all future updates to this application.
README

# Zip the bunch
cd playstore_release
zip -r ../harnessDroid_PlayStore_Release.zip ./*
cd ..

echo "Done! The release zip is located at harnessDroid_PlayStore_Release.zip"
