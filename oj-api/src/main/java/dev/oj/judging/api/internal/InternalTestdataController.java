package dev.oj.judging.api.internal;

import dev.oj.contract.JudgeEndpoints;
import dev.oj.problems.application.published.TestdataBytes;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Optional;

@RestController
@RequestMapping(JudgeEndpoints.TESTDATA)
public class InternalTestdataController {

    private final TestdataBytes testdata;

    public InternalTestdataController(TestdataBytes testdata) {
        this.testdata = testdata;
    }

    @GetMapping(path = "/{sha256}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<InputStreamResource> tai(@PathVariable String sha256) {
        Optional<InputStream> noiDung = testdata.doc(sha256);
        return noiDung.map(in -> ResponseEntity.ok(new InputStreamResource(in)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
