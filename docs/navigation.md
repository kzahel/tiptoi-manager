# Navigation

The owner's UI direction is focused screens, clear headers and navigation in
place of repeated explanatory text. Top-level destinations use a hamburger
drawer, grouped into Device, Library, Activity and Support. Child screens use
a Back arrow. System Back closes the drawer, returns through child screens,
then returns to My pen before leaving the app.

| Screen | Open from | Responsibility |
| --- | --- | --- |
| My pen | Startup / Device | Connection overview and entry points |
| Installed books | My pen | Search the connected pen's actual files |
| Storage | My pen | Free space and audio-file usage |
| Browse books | Library | Search the cached catalog; explicit refresh |
| Downloads | Library | Titles saved on this phone |
| Book details | Any title row | Status and the relevant download/install action |
| Transfers | Activity / starting an operation | Current progress or the latest transfer in this session |
| Help & about | Support | Setup steps, local-data explanation and licenses |

Raw filenames, product IDs and catalog versions are available through the
book's File details button. They are not repeated across list rows. Separate
search queries survive navigation and activity state restoration. Scans and
catalog refreshes use a small busy indicator; completed status messages do not
occupy every screen. Transfer errors remain available on the Transfers screen.

Transfer presentation is in-memory session state, not a durable history,
download queue, or background service. The underlying document-tree access,
GME integrity checks and non-overwriting install policy are unchanged.
