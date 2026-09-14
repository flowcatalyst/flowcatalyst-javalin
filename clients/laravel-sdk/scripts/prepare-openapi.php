<?php

/**
 * Pre-processes the OpenAPI JSON spec for JanePHP compatibility.
 *
 * Fixes:
 * - allOf with $ref + default in query parameters → simplified to just the $ref
 * - anyOf [string, array<string>] in query parameters → simplified to array<string>
 * - anyOf [string enum A, string enum B] in query parameters → merged to single string enum
 * - Empty schemas {} → replaced with { "type": "object" }
 */

$inputFile = $argv[1] ?? __DIR__ . '/../openapi/openapi.json';
$outputFile = $argv[2] ?? __DIR__ . '/../openapi-processed.json';

$json = json_decode(file_get_contents($inputFile), true);
if ($json === null) {
    fwrite(STDERR, "Failed to parse JSON from: {$inputFile}\n");
    exit(1);
}

$fixed = 0;

// ── OpenAPI 3.1 → 3.0 nullable type conversion ─────────────────────
// utoipa generates "type": ["string", "null"] (3.1 style).
// jane-openapi only supports "type": "string", "nullable": true (3.0).
function downgradeNullableTypes(array &$node, int &$fixed): void {
    foreach ($node as $key => &$value) {
        if ($key === 'type' && is_array($value)) {
            $nonNull = array_values(array_filter($value, fn($t) => $t !== 'null'));
            if (count($nonNull) === 1 && count($value) > count($nonNull)) {
                $node['type'] = $nonNull[0];
                $node['nullable'] = true;
                $fixed++;
            }
        } elseif (is_array($value)) {
            downgradeNullableTypes($value, $fixed);
        }
    }
    unset($value);
}

downgradeNullableTypes($json, $fixed);

foreach ($json['paths'] as $path => &$methods) {
    foreach ($methods as $method => &$operation) {
        if (!is_array($operation)) continue;

        // Fix parameters with allOf[$ref, {default}]
        if (isset($operation['parameters'])) {
            foreach ($operation['parameters'] as $i => &$param) {
                if (isset($param['schema']['allOf'])) {
                    $allOf = $param['schema']['allOf'];
                    if (count($allOf) === 2 && isset($allOf[0]['$ref']) && isset($allOf[1]['default'])) {
                        $json['paths'][$path][$method]['parameters'][$i]['schema'] = $allOf[0];
                        $fixed++;
                    }
                }

                // Fix anyOf in query parameters (Jane PHP doesn't support anyOf)
                if (isset($param['schema']['anyOf'])) {
                    $anyOf = $param['schema']['anyOf'];

                    // anyOf [string, array<string>] → array<string> (accepts both)
                    if (count($anyOf) === 2
                        && ($anyOf[0]['type'] ?? '') === 'string' && !isset($anyOf[0]['enum'])
                        && ($anyOf[1]['type'] ?? '') === 'array') {
                        $json['paths'][$path][$method]['parameters'][$i]['schema'] = $anyOf[1];
                        $fixed++;
                    }
                    // anyOf [enum A, enum B, ...] → merge into single string enum
                    elseif (array_reduce($anyOf, fn($carry, $s) => $carry && ($s['type'] ?? '') === 'string' && isset($s['enum']), true)) {
                        $merged = [];
                        foreach ($anyOf as $s) {
                            $merged = array_merge($merged, $s['enum']);
                        }
                        $json['paths'][$path][$method]['parameters'][$i]['schema'] = ['type' => 'string', 'enum' => array_values(array_unique($merged))];
                        $fixed++;
                    }
                }
            }
            unset($param);
        }

        // Fix empty response schemas
        if (isset($operation['responses'])) {
            foreach ($operation['responses'] as $code => &$response) {
                if (isset($response['content'])) {
                    foreach ($response['content'] as $mediaType => &$content) {
                        if (isset($content['schema']) && ($content['schema'] === [] || empty($content['schema']))) {
                            $json['paths'][$path][$method]['responses'][$code]['content'][$mediaType]['schema'] = ['type' => 'object'];
                            $fixed++;
                        }
                    }
                    unset($content);
                }
            }
            unset($response);
        }
    }
    unset($operation);
}
unset($methods);

// The content has been downgraded to OpenAPI 3.0 conventions above, but
// utoipa still declares "openapi": "3.1.0" at the top. jane-openapi reads
// that string to gate parsing — set it to 3.0.3 so the generator accepts
// the processed spec.
if (isset($json['openapi']) && str_starts_with((string) $json['openapi'], '3.1')) {
    $json['openapi'] = '3.0.3';
    $fixed++;
}

file_put_contents($outputFile, json_encode($json, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
echo "Processed OpenAPI spec: {$fixed} fixes applied. Output: {$outputFile}\n";
