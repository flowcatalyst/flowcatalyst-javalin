<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs\Requests;

/**
 * Payload for PUT /api/roles/{roleName}. Only provided fields are updated.
 *
 * Pass `permissions` to replace the role's permission set wholesale. Omit it
 * to leave permissions unchanged — or use `grantPermission` / `revokePermission`
 * to add/remove individual permissions without sending the whole set.
 */
final class UpdateRoleRequest
{
    /**
     * @param list<string>|null $permissions
     */
    public function __construct(
        public readonly ?string $displayName = null,
        public readonly ?string $description = null,
        public readonly ?bool $clientManaged = null,
        public readonly ?array $permissions = null,
    ) {}

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $payload = [];
        if ($this->displayName !== null) {
            $payload['displayName'] = $this->displayName;
        }
        if ($this->description !== null) {
            $payload['description'] = $this->description;
        }
        if ($this->clientManaged !== null) {
            $payload['clientManaged'] = $this->clientManaged;
        }
        if ($this->permissions !== null) {
            $payload['permissions'] = $this->permissions;
        }
        return $payload;
    }
}
