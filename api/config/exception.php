<?php
/**
 * Exception handler configuration — placeholder.
 *
 * Webman's default handler returns an HTML error page; swap in a JSON
 * ErrorHandler that returns { "code", "message" } for the API and never leaks
 * a stack trace to the client. Log full context server-side with the
 * request-id (§13.6).
 */

return [
    // 'class' => \support\exception\Handler::class,
];