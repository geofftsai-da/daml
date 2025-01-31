// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api

import com.daml.ledger.api.domain.IdentityProviderId

sealed trait IdentityProviderIdFilter

object IdentityProviderIdFilter {
  final case class ByValue(id: IdentityProviderId.Id) extends IdentityProviderIdFilter
  final case object All extends IdentityProviderIdFilter
}
