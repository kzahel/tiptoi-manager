# Navigation

The owner's UI direction is focused screens, clear headers and navigation in
place of repeated explanatory text. Top-level destinations use a hamburger
drawer, grouped into Device, Library, Activity and Support. Child screens use
a Back arrow. System Back closes the drawer, returns through child screens,
then returns to My pens before leaving the app.

| Screen | Open from | Responsibility |
| --- | --- | --- |
| My pens | Startup / Device | Known pens, connection state and last-seen inventories |
| Pen details | My pens / recognized connection | Name, identity information, inventory, storage and selected books |
| Installed books | Pen details | Search/sort a connected or explicitly labeled saved inventory |
| Storage | Pen details | Free space, audio-file usage and oldest-file review |
| Browse books | Library | Search the cached catalog; explicit refresh |
| Downloads | Library | Downloads and removal backups saved on this phone |
| Phone storage | Library / Downloads | Audio usage, individual/bulk deletion and download retention |
| Book selections | Library | Family collection, individual additions and membership |
| Review selection | Pen details / Book selections | Additions, existing matches and conflicts for the selected pen |
| Book details | Any title row | Status, pen actions and management of phone copies |
| Transfers | Activity / starting an operation | Current progress or the latest transfer in this session |
| Help & about | Support | Setup steps, local-data explanation and licenses |

Raw filenames, product IDs and catalog versions are available through the
book's File details button. They are not repeated across list rows. Separate
search queries survive navigation and activity state restoration. Scans and
catalog refreshes use a small busy indicator; completed status messages do not
occupy every screen. Transfer errors remain available on the Transfers screen.

Transfer presentation is in-memory session state, not a durable history,
download queue, or background service. The underlying document-tree access,
GME integrity checks and non-overwriting install policy remain in force.
Installed files offer removal after confirmation; a verified local backup is
required before deletion. Optional phone downloads live in the book's three-dot
menu. See [cleanup](cleanup.md) for file dates and backup behavior.
