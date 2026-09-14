<?php

declare(strict_types=1);

namespace FlowCatalyst\Auth\Rbac;

/**
 * Frozen role→permission catalogue. Built via {@see RbacBuilder::build()}.
 *
 * Wildcard rule (see {@see RbacCatalogue::matches}): `:` separator,
 * `*` suffix on segment boundaries. Mid-segment globs are not supported.
 */
final class RbacCatalogue
{
    /**
     * @param array<string, array<int, string>> $roles role => list-of-permissions
     */
    public function __construct(
        private readonly array $roles,
    ) {}

    /**
     * Union of permissions across the given roles. Unknown roles are
     * silently ignored.
     *
     * @param array<int, string> $roleNames
     * @return array<int, string>
     */
    public function resolve(array $roleNames): array
    {
        $out = [];
        foreach ($roleNames as $role) {
            if (!is_string($role)) {
                continue;
            }
            foreach ($this->roles[$role] ?? [] as $p) {
                $out[$p] = true;
            }
        }
        return array_keys($out);
    }

    /**
     * Wildcard-aware membership check.
     *
     * @param array<int, string> $permissionSet
     */
    public static function matches(array $permissionSet, string $needed): bool
    {
        $set = array_flip($permissionSet);
        if (isset($set[$needed]) || isset($set['*'])) {
            return true;
        }
        $segments = explode(':', $needed);
        for ($i = count($segments) - 1; $i > 0; $i--) {
            $prefix = implode(':', array_slice($segments, 0, $i)) . ':*';
            if (isset($set[$prefix])) {
                return true;
            }
        }
        return false;
    }
}
