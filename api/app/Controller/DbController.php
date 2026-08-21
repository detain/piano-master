<?php

namespace App\Controller;

use support\Db;
use support\Request;
use support\Response;

/**
 * GET /db/version — MySQL read proving the webman/database path works
 * (plan §20 P0.6.1).
 */
class DbController
{
    public function version(Request $request): Response
    {
        $rows = Db::select('SELECT VERSION() AS version');
        $version = $rows[0]->version ?? null;

        return json(['db_version' => $version]);
    }
}