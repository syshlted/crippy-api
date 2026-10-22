// The service crippy exports to apps that reach the vault without SAF (crippy D113). This file is
// the wire specification: what is declared here is what crosses the boundary.
//
// Two rules make it a contract rather than an interface, and both are in AGENTS.md L2:
//
//  - The interface's name is permanent. Every transaction carries "com.systemhalted.crippy.api.
//    ICrippyService" as its interface token, and crippy refuses one that does not match, so moving
//    this file to another package breaks every installed client.
//  - Methods are only ever appended. A method's transaction code is its position here, so removing,
//    reordering or inserting one changes what every installed client is asking for.
package com.systemhalted.crippy.api;

interface ICrippyService {
    /**
     * The contract version this file describes. A version adds methods after the last one and
     * changes nothing above it, so a client may call any method whose version is at most what
     * contractVersion() returns.
     */
    const int VERSION = 1;

    /**
     * The newest contract version this service implements: the VERSION of the contract crippy was
     * built against. Transaction 1, and it takes nothing, so no later version can ever need to
     * change how it is asked. A client's first call.
     */
    int contractVersion();
}
