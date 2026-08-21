<?php
/**
 * Exception handler configuration.
 *
 * '' applies to every app; the class renders every uncaught exception as JSON
 * so the API never returns HTML error pages or leaks stack traces to clients
 * (plan §13.6 / config placeholder notes).
 */

return [
    '' => \support\exception\Handler::class,
];