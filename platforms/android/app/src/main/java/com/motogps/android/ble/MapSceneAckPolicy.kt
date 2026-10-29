package com.motogps.android.ble

/**
 * Tracks acknowledgement of the latest complete map scene, not individual GATT writes.
 * The receiver ACKs the reassembled message sequence. Superseding a scene invalidates
 * outstanding ACKs, so a late ACK cannot incorrectly confirm a newer revision.
 */
internal class MapSceneAckPolicy {
    var revision: Long? = null
        private set
    var sequence: Int? = null
        private set
    var confirmedRevision: Long? = null
        private set
    var attempts: Int = 0
        private set

    fun begin(revision: Long, sequence: Int) {
        this.revision = revision
        this.sequence = sequence
        attempts = 1
    }

    fun retry(): Boolean {
        if (sequence == null || attempts >= 3) return false
        attempts++
        return true
    }

    fun acknowledge(sequence: Int, status: Int): Boolean {
        if (this.sequence != sequence || status !in 0..1) return false
        confirmedRevision = revision
        this.sequence = null
        return true
    }

    fun reset() {
        revision = null
        sequence = null
        confirmedRevision = null
        attempts = 0
    }
}
