# Private files in Work

In the connected Android preview, open a current task in **Work**. An owner,
admin, or operator can choose **Attach private file** and select one PDF,
JPEG, PNG, or plain text document up to 4 MiB in the Android picker.
Review the task, file name and size before choosing **Save and attach**.
The file stays private to the organization.

**Saved private file** means the file was verified and committed. The app
shows **Attached to task** after it independently confirms the current link.
If a response is missing, use **Retry original request** while the same task
and session are open. Closing the view clears the in-memory attempt. A file
that was already saved can remain in the organization.

The app checks the original task and organization before linking and after
reading the link. If the task changes while the link request is in flight,
the server may still record the attachment on that original task. The app
clears the old result when it detects the change.
