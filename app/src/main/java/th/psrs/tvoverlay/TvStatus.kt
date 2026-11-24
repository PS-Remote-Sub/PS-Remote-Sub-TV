package th.psrs.tvoverlay

/**
 * What the accessibility service is connected to, for the status page ([MainActivity]) in the same process.
 * The service writes it on the main thread; readers poll it.
 */
object TvStatus {
    /** The chosen PC's name, or null while no PC is heard. */
    @Volatile var pcName: String? = null

    /** The chosen PC's IP address, or null while no PC is heard. */
    @Volatile var pcHost: String? = null

    /** True while the chosen PC's `/tv/events` stream is open. */
    @Volatile var connected: Boolean = false

    /** Records the PC being followed ([pc] null: none) and whether its stream is open. */
    fun set(pc: PcBeacon?, connected: Boolean) {
        pcName = pc?.name
        pcHost = pc?.host
        this.connected = connected
    }
}
