<?php
/**
 * Logging configuration — support\Log::channel('default').
 *
 * The framework constructs its app logger from config('log')['default'] at
 * worker start (support\App.php → Log::channel('default')), so this channel
 * must exist. Plan §13.6: log to stdout → journald; per-request structured
 * JSON lines come from RequestIdMiddleware, this channel handles exception
 * reports from the JSON exception handler.
 */

use Monolog\Formatter\LineFormatter;
use Monolog\Handler\StreamHandler;
use Monolog\Logger;

return [
    'default' => [
        'handlers' => [
            [
                'class' => StreamHandler::class,
                'constructor' => [
                    'stream' => 'php://stdout',
                    'level' => Logger::DEBUG,
                ],
                'formatter' => [
                    'class' => LineFormatter::class,
                    'constructor' => [
                        'format' => "[%datetime%] %level_name% %message% %context%\n",
                        'dateFormat' => 'Y-m-d H:i:s',
                        'allowInlineLineBreaks' => true,
                    ],
                ],
            ],
        ],
    ],
];