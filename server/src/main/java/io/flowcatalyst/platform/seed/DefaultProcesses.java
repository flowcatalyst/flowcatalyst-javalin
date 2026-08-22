package io.flowcatalyst.platform.seed;

import java.util.List;

/// The platform's built-in example Process — an on-demand fulfilment
/// workflow rendered as a single Mermaid flowchart. Operators land on the
/// Processes page with one well-formed example showing the conventions
/// (three-segment code, Mermaid body, tags, application=`platform`). The
/// platform application is what surfaces under the Developer navigation, so
/// the example is associated there via its `platform:` code prefix.
///
/// Seeded once per code: the seeder only writes if no row with
/// [#EXAMPLE_CODE] exists, so operator edits are never overwritten. Edit
/// here and bump a fresh DB to re-seed.
public final class DefaultProcesses {

    public static final String EXAMPLE_APPLICATION = "platform";
    public static final String EXAMPLE_SUBDOMAIN = "fulfilment";
    public static final String EXAMPLE_PROCESS_NAME = "on-demand-flow";
    /// `application:subdomain:process-name`.
    public static final String EXAMPLE_CODE =
            EXAMPLE_APPLICATION + ":" + EXAMPLE_SUBDOMAIN + ":" + EXAMPLE_PROCESS_NAME;
    public static final String EXAMPLE_NAME = "On-Demand Fulfilment Flow";
    public static final String EXAMPLE_DESCRIPTION =
            "Reference workflow covering order placement, geocoding, picking, packing, "
                    + "trip creation, vehicle assignment, execution, and the cancel / abort branches. "
                    + "Edit or archive freely — the seeder only writes if no row with this code exists.";
    public static final String EXAMPLE_SOURCE = "CODE";
    public static final String EXAMPLE_STATUS = "CURRENT";
    public static final String EXAMPLE_DIAGRAM_TYPE = "mermaid";
    public static final List<String> EXAMPLE_TAGS = List.of("example", "fulfilment", "platform");

    /// The Mermaid source for the example workflow (pinned byte-for-byte by
    /// the seed fixture, trailing newline included).
    public static final String EXAMPLE_BODY = """
            flowchart TD
                Start([Customer places order]) --> OrderCreated[OrderCreated]
                OrderCreated --> GeoCheck{Address geocoded?}

                GeoCheck -- No --> GeoJob[Dispatch geocoding job]
                GeoJob --> GeoResult{Resolved?}
                GeoResult -- No --> GeoHold[Order on hold — geocoding failed]
                GeoHold --> NotifyCustomer[Notify customer]
                NotifyCustomer --> EndHold([Awaiting address fix])
                GeoResult -- Yes --> Reserve
                GeoCheck -- Yes --> Reserve[Reserve inventory at WMS]

                Reserve --> Stock{Stock available?}
                Stock -- No --> Backorder[Backorder created]
                Backorder --> EndBackorder([Backordered])
                Stock -- Yes --> Pick[Pick goods]

                Pick --> CancelEarly{Cancellation requested?}
                CancelEarly -- Yes --> ReleaseInv[Release inventory]
                ReleaseInv --> Refund[Refund customer]
                Refund --> EndCancel([Order cancelled])
                CancelEarly -- No --> Pack[Pack parcels]

                Pack --> Ready[FulfilmentReady]
                Ready --> CreateTrip[Create trip]
                CreateTrip --> AssignVehicle[Assign vehicle and driver]
                AssignVehicle --> Load[Load vehicle at depot]
                Load --> Depart[Depart depot]
                Depart --> EnRoute[En route]

                EnRoute --> AbortCheck{Abort signal?}
                AbortCheck -- Yes --> AbortReturn[Return to depot]
                AbortReturn --> ReverseLogistics[Restock inventory]
                ReverseLogistics --> EndAbort([Trip aborted])
                AbortCheck -- No --> Arrive[Arrive at customer]

                Arrive --> POD{Proof of delivery captured?}
                POD -- No --> Failed[Delivery failed]
                Failed --> Reschedule{Reschedule attempt?}
                Reschedule -- Yes --> CreateTrip
                Reschedule -- No --> ReverseLogistics
                POD -- Yes --> Complete[FulfilmentCompleted]
                Complete --> Invoice[Generate invoice]
                Invoice --> EndDelivered([Delivered])

                classDef happy fill:#d4edda,stroke:#28a745,color:#155724;
                classDef exception fill:#f8d7da,stroke:#dc3545,color:#721c24;
                classDef terminal fill:#e2e3e5,stroke:#6c757d,color:#383d41;
                class OrderCreated,Reserve,Pick,Pack,Ready,CreateTrip,AssignVehicle,Load,Depart,EnRoute,Arrive,Complete,Invoice happy;
                class GeoJob,GeoHold,NotifyCustomer,Backorder,ReleaseInv,Refund,AbortReturn,ReverseLogistics,Failed exception;
                class Start,EndHold,EndBackorder,EndCancel,EndAbort,EndDelivered terminal;
            """;

    private DefaultProcesses() {
    }
}
