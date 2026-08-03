# Changelog

This file records user-visible changes to Reduction Store.

## Unreleased

## 1.0.0 - 2026-08-03

### Added

- Object-state and primitive-state reduction contracts with compile-time
  generation of strongly typed stores.
- Deterministic reducer ordering, primitive state fields, and documented
  partial progress when a reducer fails.
- Immediate generated-constructor validation for null supplier and reducer
  objects, with messages identifying the reduction implementation and method.
- Guidance for fusing one-off mapping into a reducer or mapping once in the
  ingestion pipeline when several reducers share the mapped type.
- Isolated Maven consumer verification, Java 8/current-JDK CI, and Maven
  Central release rehearsal configuration for the runtime and processor
  artifacts.
- Optional `ReductionStoreDefinition` composition for selecting accessible
  reductions from other modules or JARs without changing annotation-free
  discovery.
