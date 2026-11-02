# Program update

From an extracted release package, with the existing service stopped:

```sh
python -B deploy/update-center.py --home INSTALL_DIRECTORY
```

The updater verifies the release manifest and replaces `app/`, preserving settings and data.
