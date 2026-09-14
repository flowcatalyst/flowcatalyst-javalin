<?php

declare(strict_types=1);

namespace FlowCatalyst\Auth\Rbac;

/**
 * Builder for the local RBAC catalogue.
 *
 * FlowCatalyst tokens carry roles; permissions live in the app. Define the
 * role→permission map here in code and the principal helpers
 * (`hasPermissionTo`, etc.) resolve against it locally — no platform
 * round-trip per request.
 *
 * Wildcards: `:` separator, `*` suffix at any segment boundary. `ticket:*`
 * matches `ticket:read` and `ticket:foo:bar`; `*` matches everything.
 *
 * @example
 *   $rbac = RbacBuilder::make()
 *       ->role('billing-admin')->grants(['invoice:create', 'invoice:read', 'invoice:void'])
 *       ->role('billing-viewer')->grants(['invoice:read'])
 *       ->role('support')->grants(['ticket:*'])
 *       ->build();
 */
final class RbacBuilder
{
    /** @var array<string, array<string, true>> role => permission-set */
    private array $roles = [];

    private ?string $currentRole = null;

    public static function make(): self
    {
        return new self();
    }

    public function role(string $name): self
    {
        if ($name === '') {
            throw new \InvalidArgumentException('RBAC role name cannot be empty');
        }
        $this->currentRole = $name;
        $this->roles[$name] ??= [];
        return $this;
    }

    /**
     * @param array<int, string> $permissions
     */
    public function grants(array $permissions): self
    {
        if ($this->currentRole === null) {
            throw new \LogicException('grants() called before role()');
        }
        foreach ($permissions as $p) {
            if (!is_string($p) || $p === '') {
                throw new \InvalidArgumentException(
                    "RBAC permission for role \"{$this->currentRole}\" must be a non-empty string",
                );
            }
            $this->roles[$this->currentRole][$p] = true;
        }
        return $this;
    }

    public function build(): RbacCatalogue
    {
        $frozen = [];
        foreach ($this->roles as $role => $perms) {
            $frozen[$role] = array_keys($perms);
        }
        return new RbacCatalogue($frozen);
    }
}
