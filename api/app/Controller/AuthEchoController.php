<?php

namespace App\Controller;

use App\Model\SkeletonEcho;
use support\Request;
use support\Response;

/**
 * POST /auth/echo — authenticated DB write (plan §20 P0.6.1).
 *
 * DevAuthMiddleware (route-scoped) has already validated the Bearer token by
 * the time we run. P0.6.1 placeholder only — P2.A2 replaces the token scheme
 * with RS256 JWT + rotating refresh tokens.
 */
class AuthEchoController
{
    public function echo(Request $request): Response
    {
        $message = trim((string) $request->post('message', '')) ?: 'echo';

        // Fail fast: the column is VARCHAR(255); reject instead of truncating.
        if (mb_strlen($message) > 255) {
            return json(['error' => 'message must be at most 255 characters'])->withStatus(422);
        }

        $row = SkeletonEcho::create(['message' => $message]);

        return json([
            'message' => $message,
            'id' => (int) $row->id,
        ]);
    }
}