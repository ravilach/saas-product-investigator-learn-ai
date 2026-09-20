/**
 * The general keyed configuration store backed by the {@code system_config} collection.
 *
 * <p>Deliberately generic rather than one collection per concern. It currently holds three
 * unrelated things - the auto-generated JWT signing secret, the admin-set JWT override, and the
 * crawl defaults - and adding a fourth should not require a new collection, repository, and
 * migration. Encrypted values live in {@code valueEncrypted}; non-secret structured values live in
 * {@code value}, and nothing ever writes both on one document.
 */
package com.saasinvestigator.systemconfig;
