package dtos;

import java.util.List;

// Only the source binding is changeable: it narrows reach, while name, user and bypass flags must
// not move under a key already in circulation. Empty or null lifts the binding.
public record ApiKeyUpdateDto(
        List<String> allowedCidrs) {
}
