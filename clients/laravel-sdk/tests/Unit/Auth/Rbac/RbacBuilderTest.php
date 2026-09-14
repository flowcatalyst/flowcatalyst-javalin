<?php

declare(strict_types=1);

namespace FlowCatalyst\Tests\Unit\Auth\Rbac;

use FlowCatalyst\Auth\Rbac\RbacBuilder;
use FlowCatalyst\Auth\Rbac\RbacCatalogue;
use PHPUnit\Framework\TestCase;

final class RbacBuilderTest extends TestCase
{
    public function test_unions_permissions_across_roles(): void
    {
        $rbac = RbacBuilder::make()
            ->role('a')->grants(['p1', 'p2'])
            ->role('b')->grants(['p2', 'p3'])
            ->build();
        $got = $rbac->resolve(['a', 'b']);
        sort($got);
        $this->assertSame(['p1', 'p2', 'p3'], $got);
    }

    public function test_ignores_unknown_roles(): void
    {
        $rbac = RbacBuilder::make()->role('a')->grants(['p1'])->build();
        $this->assertSame(['p1'], $rbac->resolve(['a', 'ghost']));
    }

    public function test_multiple_grants_on_same_role_accumulate(): void
    {
        $rbac = RbacBuilder::make()
            ->role('a')->grants(['p1', 'p2'])
            ->role('a')->grants(['p3'])
            ->build();
        $got = $rbac->resolve(['a']);
        sort($got);
        $this->assertSame(['p1', 'p2', 'p3'], $got);
    }

    public function test_returns_empty_when_no_roles_supplied(): void
    {
        $rbac = RbacBuilder::make()->role('a')->grants(['p1'])->build();
        $this->assertSame([], $rbac->resolve([]));
    }

    public function test_rejects_empty_role_name(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        RbacBuilder::make()->role('');
    }

    public function test_rejects_empty_permission(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        RbacBuilder::make()->role('a')->grants(['']);
    }

    public function test_wildcard_matches_at_any_segment_depth(): void
    {
        $this->assertTrue(RbacCatalogue::matches(['billing:*'], 'billing:read'));
        $this->assertTrue(RbacCatalogue::matches(['billing:*'], 'billing:invoice:read'));
        $this->assertFalse(RbacCatalogue::matches(['billing:*'], 'ticket:read'));
    }

    public function test_full_wildcard_matches_everything(): void
    {
        $this->assertTrue(RbacCatalogue::matches(['*'], 'anything:goes:here'));
    }

    public function test_literal_match(): void
    {
        $this->assertTrue(RbacCatalogue::matches(['billing:read'], 'billing:read'));
        $this->assertFalse(RbacCatalogue::matches(['billing:read'], 'billing:write'));
    }
}
