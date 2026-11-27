// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox

import eu.schicht8.meavox.ui.licenses.ThirdPartyNotice

/** What only MeaVox Max contains: the NewPipe Sources and the libraries they bring (ADR 1, ADR 24). */
internal object FlavorNotices {
    val notices: List<ThirdPartyNotice> = listOf(
        ThirdPartyNotice("NewPipeExtractor", "GPL-3.0-or-later", listOf("GPL-3.0.txt"), "https://github.com/TeamNewPipe/NewPipeExtractor"),
        ThirdPartyNotice("nanojson", "Apache-2.0", listOf("Apache-2.0.txt"), "https://github.com/TeamNewPipe/nanojson"),
        ThirdPartyNotice("jsoup", "MIT", listOf("max/MIT-jsoup.txt"), "https://jsoup.org"),
        ThirdPartyNotice("Mozilla Rhino", "MPL-2.0", listOf("max/MPL-2.0-Rhino.txt"), "https://github.com/mozilla/rhino"),
        ThirdPartyNotice("Protocol Buffers (Java lite)", "BSD-3-Clause", listOf("max/BSD-3-Clause-protobuf.txt"), "https://protobuf.dev"),
        ThirdPartyNotice("JSR 305 annotations", "Apache-2.0", listOf("Apache-2.0.txt"), "https://github.com/findbugsproject/findbugs"),
    )
}
