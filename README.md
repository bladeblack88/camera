# Ivideon Fullscreen Camera

Android app scaffold for a single Ivideon camera.

## Current status

This repository contains the Android player foundation. Live API authentication must be configured with the integration credentials issued for your Ivideon Open API account. Do not commit credentials, tokens, or camera account passwords.

## Build

The GitHub Actions workflow builds a debug APK on pushes to `main` and manual runs. Download it from the workflow run's Artifacts section.

## Configuration

On first launch, the app asks for the OAuth/API settings and Ivideon account credentials. These are stored locally using Android encrypted preferences. The app then opens the configured camera in fullscreen and retries the live stream after connection failures.

The historical Ivideon OAuth example uses `POST /auth/oauth/token` with HTTP Basic client credentials and a password grant. Confirm the OAuth host and client credentials with Ivideon before use. The app defaults to the documented legacy host but allows changing it in setup.
