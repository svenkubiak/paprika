package controllers;

import filters.api.ApiAuthFilter;
import filters.api.ApiMultipartFilter;
import io.mangoo.annotations.FilterWith;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * mangoo runs filters in declaration order, and {@link ApiAuthFilter} only sees a multipart body
 * after {@link ApiMultipartFilter}; reversed, every body-dependent rule is bypassed via multipart.
 */
class CollectionControllerFilterOrderTest {

    @Test
    void multipartParsingHappensBeforeTheRulesAreEvaluatedOnCreate() {
        assertMultipartBeforeAuth("create");
    }

    @Test
    void multipartParsingHappensBeforeTheRulesAreEvaluatedOnUpdate() {
        assertMultipartBeforeAuth("update");
    }

    private void assertMultipartBeforeAuth(String methodName) {
        List<Class<?>> filters = filtersOf(methodName);

        int multipart = filters.indexOf(ApiMultipartFilter.class);
        int auth = filters.indexOf(ApiAuthFilter.class);

        assertThat("ApiMultipartFilter is missing from " + methodName, multipart, greaterThanOrEqualTo(0));
        assertThat("ApiAuthFilter is missing from " + methodName, auth, greaterThanOrEqualTo(0));
        assertThat("ApiMultipartFilter must run before ApiAuthFilter on " + methodName, multipart, lessThan(auth));
    }

    private List<Class<?>> filtersOf(String methodName) {
        Method method = Arrays.stream(CollectionController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No method " + methodName + " on CollectionController"));

        FilterWith filterWith = method.getAnnotation(FilterWith.class);
        assertThat("No @FilterWith on " + methodName, filterWith != null, is(true));

        return List.of(filterWith.value());
    }
}
