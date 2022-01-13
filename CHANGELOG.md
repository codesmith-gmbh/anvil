# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## Unreleased

### Changed

- BREAKING CHANGE: the docker creation has significantly changed to support the following features:
  - jre as well as jdk runtimes
  - with java version > 11, jre images are built based on [bitnami minideb](https://hub.docker.com/r/bitnami/minideb) with jlink.

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
