# Team invitations in the connected Android preview

In a ready connected organization, its owner can open **Manage team
invitations**. The screen reads current active, reserved and available
seats from the organization entitlements endpoint. The owner chooses
Operator or Viewer, then explicitly issues an invitation. Invitation
history is optional and paginates independently of issuing.

The client exchanges the current workspace session for an account grant.
It uses the account security invitation endpoints with the selected
organization. Confirmed issuance is tied to the exact response and code
preview. The preview must agree on invitation, organization, role and
expiry before the app offers Copy. A replay may confirm an invitation
without returning its code. The invitee can use the existing account
security invitation flow.

The code stays in the current screen controller's memory until it expires,
is dismissed or copied, or the screen loses foreground or closes. Copy
sends it to Android's clipboard and marks it sensitive. Android or other
apps may retain clipboard content. Share the code privately.

An owner can review and revoke an unexpired pending invitation. The
client sends the reviewed invitation identifier and version. If a write
cannot be confirmed, Retry uses the original request body and key in the
same session. The client checks the owner scope before and after requests
and refreshes the server's seat counts after a confirmed write.

The local tests use synthetic contract responses. Mounted service
qualification is tracked separately.
