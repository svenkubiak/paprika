package dtos;

import java.util.List;

/**
 * The changeable part of an API key - which is only its source binding.
 * <p>
 * Name and bound user are what the key <em>is</em>, and {@code bypassRules}/{@code bypassHooks}
 * hand out reach, so none of them may move under a credential that is already in circulation.
 * {@code allowedCidrs} only ever narrows where the key works, and the addresses it names change
 * on their own when a host moves - so this one endpoint exists, and it carries nothing else.
 */
public record ApiKeyUpdateDto(
        /** The new list of ranges. An empty list lifts the binding; {@code null} does the same. */
        List<String> allowedCidrs) {
}
