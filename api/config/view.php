<?php
/**
 * View configuration.
 *
 * The API returns JSON only; the raw handler keeps the view() helper from
 * fatally erroring if it is ever called by mistake. No template engine is
 * installed for the spike.
 */

use support\view\Raw;

return [
    'handler' => Raw::class,
    'options' => [
        'view_path' => app_path() . '/view',
    ],
];