package com.example.model;

import rawit.TaggedValue;

/**
 * Tag annotation for user ID values.
 * Strict mode: warns on tagged↔untagged assignments (except literals/constants).
 */
@TaggedValue(strict = true)
public @interface UserId { }
