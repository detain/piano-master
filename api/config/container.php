<?php
/**
 * DI container configuration.
 *
 * support\Container::instance() reads config('container'); the framework
 * requires it to be a PSR-11 container instance. config/container.php is
 * excluded from the recursive Config::load and read on demand.
 */

return new \Webman\Container();