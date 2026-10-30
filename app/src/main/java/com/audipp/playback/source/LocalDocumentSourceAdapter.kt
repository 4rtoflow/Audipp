package com.audipp.playback.source

import com.audipp.domain.model.Availability
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.SourceKind

class LocalDocumentSourceAdapter(
    private val capabilities: PlaybackCapabilities,
    private val access: suspend (String) -> Availability,
) : SourceAdapter {
    override suspend fun resolve(request: ResolutionRequest): AdapterResolution {
        val source = request.variant.source
        val uri = source.localDocumentUri
        if (source.kind != SourceKind.LOCAL || request.context.target.kind != TargetKind.DEVICE ||
            uri == null || !isContentUri(uri)
        ) return AdapterResolution.Unavailable(ResolutionFailure.INVALID_DESCRIPTOR)
        availabilityFailure(access(uri))?.let { return AdapterResolution.Unavailable(it) }
        return AdapterResolution.Available(request.variant.variantId, source.sourceId,
            SourceDescriptor.LocalDocument(uri), capabilities)
    }
}
