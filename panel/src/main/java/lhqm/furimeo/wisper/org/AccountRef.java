package lhqm.furimeo.wisper.org;

import java.util.UUID;

/**
 * The four things this package needs to know about a person.
 *
 * <p>{@code org} owns {@code member}, whose foreign key points at {@code account}, so it
 * has to be able to turn an email address into an id when somebody is invited and an id
 * into a label when an audit entry is written. It deliberately does not model the
 * account: passwords, second factors and sessions belong to {@code auth}, and this record
 * carries nothing that would tempt a reader into thinking otherwise.
 *
 * <p>The dependency runs this way round on purpose. {@code auth} depends on {@code org}
 * (panel-ports.md §6); an {@code org -> auth} import would close the loop and neither
 * package would compile.
 *
 * @param id          the account
 * @param email       lower-cased, as the column stores it
 * @param displayName what a member list shows next to the address
 * @param active      false for a suspended account, which may not be invited anywhere
 */
public record AccountRef(UUID id, String email, String displayName, boolean active) {

    /** The label an audit entry keeps after the account itself is deleted. */
    public String auditLabel() {
        return email;
    }
}
