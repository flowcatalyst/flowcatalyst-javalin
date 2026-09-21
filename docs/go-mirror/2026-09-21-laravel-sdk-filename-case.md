# Go fix: two Laravel SDK files are indexed under the wrong case

Found 2026-09-21 while bringing `clients/laravel-sdk` to Go `52993a0`.

In **both** repositories git's index holds

- `clients/laravel-sdk/src/Generated/Model/RoleAssignmentDto.php`
- `clients/laravel-sdk/src/Generated/Normalizer/RoleAssignmentDtoNormalizer.php`

while the classes inside are `RoleAssignmentDTO` and `RoleAssignmentDTONormalizer`. On macOS the
filesystem is case-insensitive, the working copy shows `…DTO.php`, git (`core.ignorecase`) sees no
change, and everything loads. On Linux — CI, and every Packagist consumer — the checkout is
`…Dto.php`, PSR-4 looks for `RoleAssignmentDTO.php`, and the class does not autoload. Anything that
deserialises a role assignment through the generated client fails there.

Fixed here (`git mv` through a temporary name, the only way on a case-insensitive filesystem). A
scan of every PHP file under `src/` for "indexed filename ≠ declared class" finds no other case.

**Go side, same two commands:**

```
cd clients/laravel-sdk/src/Generated
git mv Model/RoleAssignmentDto.php Model/x.tmp && git mv Model/x.tmp Model/RoleAssignmentDTO.php
git mv Normalizer/RoleAssignmentDtoNormalizer.php Normalizer/x.tmp && git mv Normalizer/x.tmp Normalizer/RoleAssignmentDTONormalizer.php
```

Until Go does the same, `tools/sdk-drift.sh` stays green on macOS (it compares working copies) and
would report these two files on Linux. The published split repository
(`split-laravel-sdk.yml`) inherits whichever index it is cut from — check the released package.
