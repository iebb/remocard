# RemoCard

Standalone Android companion app for Remote SIM management.

## Building and Releasing

To create a new release and upload it to GitHub:

1. Ensure you have the [GitHub CLI](https://cli.github.com/) installed and authenticated:
   ```bash
   brew install gh
   gh auth login
   ```

2. Run the release script with the new version name:
   ```bash
   ./release.sh 1.1
   ```

The script will:
- Increment `versionCode`.
- Set `versionName` to `1.1`.
- Build a **Universal APK** (Full) and an **arm64-v8a APK**.
- Create a GitHub release and upload both assets.
- Push the version bump and tags to the repository.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
