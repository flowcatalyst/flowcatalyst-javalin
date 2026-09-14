<?php

return [
    'openapi-file' => __DIR__ . '/openapi-processed.json',
    'namespace' => 'FlowCatalyst\\Generated',
    'directory' => __DIR__ . '/src/Generated',
    'strict' => false,
    'clean-generated' => true,
    // PHP-CS-Fixer over the whole generated tree adds 1–3 silent minutes to
    // every regen — Jane shows no progress while it runs. Generated code is
    // throwaway; if you want it formatted, run `vendor/bin/php-cs-fixer fix
    // src/Generated` separately.
    'use-fixer' => false,
    'fixer-config-file' => null,
    'throw-unexpected-status-code' => false,
];
