# LAME 3.100 (vendored)

`lame-3.100/` holds the parts of the LAME 3.100 release that MeaVox builds, **unmodified**, so the
build needs no network access (#21, F-Droid). They come from the release tarball
`lame-3.100.tar.gz` (<https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz>,
SHA-256 `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`):

- `include/`, `libmp3lame/`: the encoder library and its public header, built by
  `export/src/main/cpp/CMakeLists.txt` as `libmp3lame.so`;
- `COPYING`, `LICENSE`, `README`, `ChangeLog`, `API`: as in the release.

LAME is licensed under the GNU LGPL, version 2 or later (`lame-3.100/COPYING`, and
`docs/licenses/LGPL-2.0-LAME.txt`). MeaVox links it dynamically, so it can be replaced (ADR 8).

To check the copy against the release:

```sh
curl -LO https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
echo "ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e  lame-3.100.tar.gz" | sha256sum -c -
tar xzf lame-3.100.tar.gz -C /tmp
for p in include libmp3lame COPYING LICENSE README ChangeLog API; do diff -r /tmp/lame-3.100/$p third_party/lame-3.100/$p; done
```
