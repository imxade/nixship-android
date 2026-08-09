# Licensing

This repository contains work under more than one license. A file's existing copyright and license
notice takes precedence.

The inherited `termux/termux-app` application code is released under
[GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html). Changes to and distributions of that code
remain subject to GPLv3; the addition of an Apache license does not relicense upstream work.

Original Nix Ship-specific contributions that carry an `SPDX-License-Identifier: Apache-2.0`
notice, along with the original Nix Ship brand assets and store-listing copy, are:

```text
Copyright 2026 Rituraj Basak
```

and are licensed under the [Apache License 2.0](LICENSE-APACHE-2.0.txt). When Apache-licensed
components are distributed together with the GPLv3 application, the combined distribution must
also satisfy the GPLv3 terms.

### Exceptions

- [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) code is used which is released under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) license. Check [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator) libraries.
- Check [`termux-shared/LICENSE.md`](termux-shared/LICENSE.md) for `termux-shared` library related exceptions.
