package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionFilter;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionListItem;
import dev.oj.judging.domain.JudgingException;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.web.CursorPage;
import org.springframework.stereotype.Service;

@RequiresRole
@Service
public class ListMySubmissionsUseCase {

    private final CurrentUserProvider currentUser;
    private final SubmissionRepository submissions;
    private final AppProperties.Page page;

    public ListMySubmissionsUseCase(CurrentUserProvider currentUser,
                                    SubmissionRepository submissions,
                                    AppProperties properties) {
        this.currentUser = currentUser;
        this.submissions = submissions;
        this.page = properties.page();
    }

    public CursorPage<SubmissionListItem> list(String cursor, Integer size, SubmissionFilter filter) {
        long userId = currentUser.current().id();
        int pageSize = CursorPage.clampSize(size, page.defaultSize(), page.maxSize());
        return submissions.listForUser(
                userId,
                filter == null ? SubmissionFilter.none() : filter,
                parseCursor(cursor),
                pageSize);
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            throw JudgingException.invalidCursor(cursor);
        }
    }
}
