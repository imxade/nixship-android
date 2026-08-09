# Notices

Nix Ship-specific original contributions and brand assets:

```text
Copyright 2026 Rituraj Basak
Licensed under the Apache License, Version 2.0.
```

See `LICENSE-APACHE-2.0.txt` for the full terms and `LICENSE.md` for scope. This notice does not
change the licenses of inherited components.

This repository is derived from:

- `nix-community/nix-on-droid-app`, upstream commit
  `e87b6091bffa7b6eafb1b59cc7824f5692441cd0`;
- `termux/termux-app` and its included terminal libraries.

The Termux application portions are GPLv3-only. Terminal emulator/view portions include Apache 2.0
code and `termux-shared` has the exceptions documented in its own license file. The existing
copyright, license, and attribution files remain authoritative.

The Nix Ship control plane is fetched from the repository and exact revision declared in
`config/product.json` and embedded as source during the Nix APK build. Its own license files are
preserved inside the APK asset tree.
