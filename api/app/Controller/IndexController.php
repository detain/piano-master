<?php

namespace App\Controller;

use support\Request;
use support\Response;

/**
 * GET / — static JSON, no DB (plan §20 P0.6.1).
 */
class IndexController
{
    public function index(Request $request): Response
    {
        return json([
            'service' => 'keyquest-api',
            'ok' => true,
        ]);
    }
}