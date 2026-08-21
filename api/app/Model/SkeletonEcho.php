<?php

namespace App\Model;

use support\Model;

/**
 * skeleton_echo — P0.6.1 spike table proving the authenticated DB write path
 * (plan §20 P0.6.1). Replace with real domain tables in P1.
 */
class SkeletonEcho extends Model
{
    protected $table = 'skeleton_echo';

    public $timestamps = false; // created_at is a DB-side DEFAULT CURRENT_TIMESTAMP.

    protected $fillable = ['message'];
}