# Maintainer: abyts1
pkgname=ember-git
pkgver=1.0.0
pkgrel=1
pkgdesc='Sun-aware screen colour temperature daemon for Hyprland (drives hyprsunset)'
arch=('x86_64' 'aarch64')
url='https://github.com/abyts1/ember'
license=('MIT')
depends=('glibc' 'hyprsunset')
makedepends=('git')
provides=('ember')
conflicts=('ember')
source=("$pkgname::git+$url.git")
sha256sums=('SKIP')

pkgver() {
  cd "$pkgname"
  printf '1.0.0.r%s.%s' "$(git rev-list --count HEAD)" "$(git rev-parse --short HEAD)"
}

build() {
  cd "$pkgname"
  make
}

package() {
  cd "$pkgname"
  make PREFIX=/usr DESTDIR="$pkgdir" install
}
