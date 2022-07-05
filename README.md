# ticket-streaming


### Things to think about
* If we would like to enrich this very basic event stream by downloading the
  corresponding full ticket audit log for each ticket event we see, how would your
  code change?
    > Not much will change, I will create another stream for the audit log entirely, so that as each data is coming 
    from the ticket stream, i will channel them directly to the audit log stream where the corresponding audit can be pulled.
    this can be done by creating a new message `ProcessAuditLog(source)` and changing the initial `ProcessStream` to `ProcessTicketStream`
    and then process the audit payloads stream there.
  > 

* What kinds of things could go wrong in this service (and its external dependency on
  Zendesk) and how would we deal with them? 