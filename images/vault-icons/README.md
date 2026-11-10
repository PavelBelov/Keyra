# Database icons

69 database icons, indexed by KDBX icon ID (0–68), copied from
[KeePassXC revision 6bcb901aefdd6c7a525466d7dfe099c5ebc13c0e](https://github.com/keepassxreboot/keepassxc/tree/6bcb901aefdd6c7a525466d7dfe099c5ebc13c0e/share/icons/database).

These files retain their upstream licenses, rather than the default license of
Keyra's own artwork: 56 MIT, 12 CC0-1.0, and C64_Apple.svg conservatively treated
as GPL-2.0-or-later according to upstream's `GPL-2+` annotation. That annotation
is placed in the upstream Copyright field without a separate License field;
it is preserved verbatim in [UPSTREAM-COPYING](UPSTREAM-COPYING).

[PROVENANCE.json](PROVENANCE.json) maps each original SVG and generated PNG to its
license and hashes. All 69 SVG Git blob hashes and the upstream COPYING match
the pinned revision. All 69 PNG files match rendering with resvg 2.6.2 at 112px.
Full texts and attribution are also shipped in `app/src/main/assets/licenses/`
under `KeePassXC-Icons-*`. These are database icons; Keyra's application logo is
separate project artwork.

Android PNG resources are generated at 112px (28dp, xxxhdpi).
To regenerate from the repository root:

```powershell
npm install --prefix .tools/icon-converter --no-audit --no-fund @resvg/resvg-js@2.6.2
node images/vault-icons/generate.cjs
```

The converter is an MPL-2.0 development tool and is not shipped in the APK.
The Android build uses the committed PNG resources and does not invoke Node.
