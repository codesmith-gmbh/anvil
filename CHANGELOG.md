# Change Log

All notable changes to this project will be documented in this file. This change log follows the conventions
of [keepachangelog.com](http://keepachangelog.com/).

## Unreleased

### Added

- :include-locales options for the jlink runtime

## 0.10.242 (2024-02-10)

### Fixed

- use platform option in the docker build command.

## 0.10.239 (2024-02-10)

## 0.10.236 (2024-02-10)

## 0.10.233 (2024-02-10)

### Added

- option to specify the docker platform architecture.

## 0.10.230 (2024-02-08)

### Changed

- bump docker base image versions
- bump dependencies

## 0.10.227 (2023-12-17)

### Added

- fix specify env vars and volumes for the generate dockerfile.

### Changed

- bump docker base image versions

## 0.10.224 (2023-12-17)

### Added

- specify env vars and volumes for the generate dockerfile.

### Changed

- bump dependencies

## 0.10.219 (2023-11-18)

## 0.10.216 (2023-11-18)

### Changed / Fixed

- BREAKING CHANGE: API change: basis create function as argument instead of basis themselves, as the latter are useless
  as we bind to `*project-root*` inside the functions.
- BREAKING CHANGE: lib/jar returns a structure.
- BREAKING CHANGE: remove default gitlab basis creation.

## 0.10.212 (2023-11-12)

### Changed

- bump dependencies
- slightly better logging

## 0.10.205 (2023-11-04)

## 0.10.200 (2023-10-13)

## 0.10.192 (2023-08-21)

### Fixed

- use full name (namespace + name) for docker image tags.

## 0.10.187 (2023-08-20)

## 0.10.185 (2023-08-20)

### Changed

- bump dependencies

## 0.10.180 (2022-12-10)

### Changed

- bump dependencies

## 0.10.177 (2022-09-26)

### Fixed

- apps: put app jar(s) first in the class path for resource loading

### Changed

- apps: put the libraries in '/lib/anvil' instead of '/lib'

## 0.10.172 (2022-06-23)

### Fixed

- copy resources with aot compiling.

## 0.10.169 (2022-06-22)

### Added

- add `Implementation-Version` to the jar manifest.
- apps: new `:clj-runtime` keyword for apps; replacing `:main-namespace` for more clojure runtime settings.
- apps: possibility to configure the run script in docker artifacts via the multimethod `apps/clj-run-script` and the
  keyword `:script-type` in the `:clj-runtime` map.
- apps: 2 values for `:script-type` if the `:clj-runtime` map:
    - `:clojure.main`: for invocation of the `:main-namespace` via clojure.main
    - `:class`: for invocation via an AOTed gen-class namespace.

### Changed

- updated to org.clojure/tools.deps.alpha 0.14.1212

## 0.9.163 (2022-06-16)

## 0.8.151 (2022-05-07)

- update dependencies.

## 0.8.148 (2022-04-21)

### Changed

- newer version of forked deps-deploy.

## 0.8.145 (2022-04-21)

### Changed

- better use of root-dir (with proper target-dir/class-dir resolution)

### Fixed

- When package jars, use the root of the project as *project-root* in clojure deps to properly resolve relative paths.

## 0.7.140 (2022-04-18)

### Changed

- Support for poly libraries

## 0.7.135 (2022-04-04)

## 0.7.134 (2022-04-04)

### Changed

- Breaking change in git-release!: the shape artifact data has changed: more that 1 artifact possible.

## 0.6.123 (2022-03-11)

## 0.6.119 (2022-02-21)

### Changed

- add the jdk.unsupported module to the default anvil profile.

## 0.5.116 (2022-02-21)

## 0.5.113 (2022-02-20)

## 0.4.107 (2022-02-20)

## 0.4.101 (2022-02-12)

### Added

- gitlab scm

## 0.4.97 (2022-01-23)

## 0.4.93 (2022-01-23)

## 0.4.90 (2022-01-23)

### Changed

- releasing: sign git tag
- releasing: Add Release LocalDate in CHANGELOG.

## 0.4.84

### Changed

- BREAKING CHANGE: update readme needs different keys, supports docker images.

## 0.3.79

### Added

- clj-kondo as a linter.
- pass description-data for jars for docker images.

## 0.3.72

### Added

- aot support.

### Changed

- BREAKING CHANGE: the docker creation has significantly changed to support the following features:
    - jre as well as jdk runtimes
    - with java version > 11, jre images are built based on [bitnami minideb](https://hub.docker.com/r/bitnami/minideb)
      with jlink.

## 0.2.65

### Added

- Support for description data (inspired by Lambdaisland tooling)

### Changed

- consistent `:class-dir` naming (instead of mixing `:class-dir` and `:classes-dir`)

## 0.2.60

## 0.2.55

### Changed

- Restarted with own utilities tools.build
- Docker image with base image with dependencies
- aot missing.

## 0.1.35

### Added

- Support for build tools basis options
- Test runners from [build-clj](https://github.com/seancorfield/build-clj) by Sean Corfield; modified for basis options

### Removed

- Ad-hoc Gitlab support

## 0.1.31

### Added

- Ad-hoc Gitlab support

## 0.1.29

### Added

- a function `sh!` to execute shell commands and redirect I/O

### Modified

- some git functions with side-effectes uses now `sh!`

## 0.1.27

### Added

- Shell utilities

## 0.1.25

### Added

- Allow to override base docker images

### Modified

- Slim image for openjdk/jdk17

## 0.1.23

### Added

- Base AOT/Dockerfile/buildscript generation.
