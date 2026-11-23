// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** The project's own Lint checks. */
class MeaVoxIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(HardcodedUiTextDetector.ISSUE)

    override val api: Int = CURRENT_API

    override val vendor: Vendor = Vendor(vendorName = "MeaVox", feedbackUrl = "https://github.com/bluepoke/MeaVox/issues")
}
