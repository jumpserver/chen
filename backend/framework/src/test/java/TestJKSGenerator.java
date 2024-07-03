import org.jumpserver.chen.framework.ssl.JKSGenerator;

public class TestJKSGenerator {

    private static final String CA_CERT = """
            -----BEGIN CERTIFICATE-----
            MIIDIjCCAgqgAwIBAgIBATANBgkqhkiG9w0BAQsFADA8MTowOAYDVQQDDDFNeVNR
            TF9TZXJ2ZXJfOC4wLjMyX0F1dG9fR2VuZXJhdGVkX0NBX0NlcnRpZmljYXRlMB4X
            DTIzMDkxMTEwNDEwNVoXDTMzMDkwODEwNDEwNVowPDE6MDgGA1UEAwwxTXlTUUxf
            U2VydmVyXzguMC4zMl9BdXRvX0dlbmVyYXRlZF9DQV9DZXJ0aWZpY2F0ZTCCASIw
            DQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKfGsMOxG+HKpPMCXjgV0nYTMv71
            eMfOYzH+0suZZiffe+TOWw0jYypSzSxuONfrAE4tEg5HVhGboC1A6MuMsO+cQDxj
            h6zQviDFAKltElsJhZ1GpHAPKw44icn43eHv+HGjeifn4hNOGVp7wMOa0sj8Awrl
            v6GofhUkPIdxuUAaHf5x4IJC7HWtPmOYog6NmI7u6VVdxIolFWUeABhAPJVhcQVz
            uJ6dAI8ViZ41EAFNtlBtSQIIeDyImwdjfC6Q6Q0Zre4hS4Oj1bM5MpesGVEpE2aj
            q9r2SH2ZgmG/n/0f8RPcQt6r1wDX9lFew4o15kAI++nuXcnuuvgakQGsH5UCAwEA
            AaMvMC0wDAYDVR0TBAUwAwEB/zAdBgNVHQ4EFgQUKWCqe5PhnSIQ/4NOMKCqFR6e
            hGYwDQYJKoZIhvcNAQELBQADggEBAASVp72HMzoUJ1z9EVEJvGCJ7c77ppdOpg2k
            zHt7UnAlYBPooJMRpbTIpK6n6ZHx8nfMa+ICKmf1zZ4wFchPDaWqaGDII9SIWSaO
            kj6L8lhTmm/foDMJ7KPNHU7adHpDmkaee83ATn9j4/IIctH0gVmDIx9TyKBKaCnC
            YnIect5CFvH3k2KhSXyl1fme1qdM7yOLEs76xoqe1SAH9UHtJuDTPp9yNHvzufIg
            RO3M+vInB428xTcwZekfg9Ri0BdXY2Yov8iZPDuDyFwx+aVp0C45LK4cKqCIAdRW
            oB9l/3fqoAGfIUKy6GaBDCUUXLpLyvBKyzf2+XM+94J8W12hGVg=
            -----END CERTIFICATE-----
            """;
    private static final String CLIENT_CERT = """
            -----BEGIN CERTIFICATE-----
            MIIDRDCCAiygAwIBAgIBAzANBgkqhkiG9w0BAQsFADA8MTowOAYDVQQDDDFNeVNR
            TF9TZXJ2ZXJfOC4wLjMyX0F1dG9fR2VuZXJhdGVkX0NBX0NlcnRpZmljYXRlMB4X
            DTIzMDkxMTEwNDEwNVoXDTMzMDkwODEwNDEwNVowQDE+MDwGA1UEAww1TXlTUUxf
            U2VydmVyXzguMC4zMl9BdXRvX0dlbmVyYXRlZF9DbGllbnRfQ2VydGlmaWNhdGUw
            ggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDlBQnHUoXuVk8YFxh/vsHm
            jhWshteXGhH1l6eHn2Sy5ZSApHUY42jsfAm1wEz2b0YCdHYxao3LxXhq1zuBJkCM
            KGTeJNiK8Lo4xsry/tH2BaUNefAi4HO5XsYbSiZctkdcajHdY1GO4U73z79cJwDh
            Vr85JEq+LME3wl+KwJeBb1zFmBKbK8JlvNgloewdOjEQ0Oizhl8/IIRxi0Buxogt
            q+BtGBBMIbSkTVoNCoGdKM1YJND3LeOM/GH9nMgCefBqGTxKemq8VuHygUvnr/0j
            vpMSLaQUn5oPJQaJq15WOaGs5NKogc12YVWdgL5ql/458bIjwsl8JqcNLkFDVr7t
            AgMBAAGjTTBLMAkGA1UdEwQCMAAwHQYDVR0OBBYEFFRXzas0GZR8DdPWAo8IEkwp
            Aja8MB8GA1UdIwQYMBaAFClgqnuT4Z0iEP+DTjCgqhUenoRmMA0GCSqGSIb3DQEB
            CwUAA4IBAQASuLFdKJidwtBBAoP0/NOuTpB//xvD1wqsFpSiZV6pvGTGGw4mDVCR
            kH7LvvATVmmUN6FqszCJ2bzxb2DOJ8PlnWGixvH19MpruLfNXNjnjZ8JlN/YhZOM
            OlVpGJUxbgiz4uP/J1Z0QKmzWt+Dtcoc0D+3+EXrEm9yHd1HVog9MpfwTvhzri68
            JMOaTaQjgkd5hYCa0FiTSJvBDOBhGwUtNffNCuH5IDsoadP9WIxMPZIY4KhxymZ1
            B8e5WtHW/RsfxY/3PUoUvlkqRjWDwljUQAyP2ayd5hS5Dp6YBT4XN5Fx9ja4a6bT
            zLV2Qp9wErgL5DxHUmCXDaG5Q9YZUtWq
            -----END CERTIFICATE-----
            """;
    private static final String CLIENT_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDlBQnHUoXuVk8Y
            Fxh/vsHmjhWshteXGhH1l6eHn2Sy5ZSApHUY42jsfAm1wEz2b0YCdHYxao3LxXhq
            1zuBJkCMKGTeJNiK8Lo4xsry/tH2BaUNefAi4HO5XsYbSiZctkdcajHdY1GO4U73
            z79cJwDhVr85JEq+LME3wl+KwJeBb1zFmBKbK8JlvNgloewdOjEQ0Oizhl8/IIRx
            i0Buxogtq+BtGBBMIbSkTVoNCoGdKM1YJND3LeOM/GH9nMgCefBqGTxKemq8VuHy
            gUvnr/0jvpMSLaQUn5oPJQaJq15WOaGs5NKogc12YVWdgL5ql/458bIjwsl8JqcN
            LkFDVr7tAgMBAAECggEAApvQlTMk8GaORxV4Q3g+SCyFJa5xhTiYTMZZ86sGoB0K
            WH7HEK4Cc3MkPyw+FC6HSx7de8mdbN1Gdl0WQe3YHWCWaYtc5hRA4sWs6SCq7pT4
            4NLrP9JzrwBb3FK3ZiXzYSOahs2LT8fUOPFmWhvdoDN3QSTfLxRebQ6rPO7dqu0+
            ybXqYIzDtkEUiTSfkf84sep1iJCf5bWUWRBBnW/iGzEB/CeTxQXmNu0CjUnACpMS
            JCznioZvOuuY47QBD+aBkaD+Vy/7mX8FRUPS+th5s3WnS3tR0vN9yUq3Merv+lpm
            0xcb/zJdI045QhU0m0vIA1aaXGTTQAjetc8oNhpUMQKBgQDpfWRiBKDe2hYPe59i
            2mHGVu7HLYcBQP5vBC8KrCPhniq81Q69Pq9p0Lq//I33m/ulhUmtsuwV7ramuyxz
            OUZJV+knn4DoQ6gCqfmkMnqR+/eqGVJwB++5EGwaV8zDu92oqgYwvL1VVzRCN8BN
            bgM6dHsn1mYdOQB/1t7xTDxd9QKBgQD7GVJaLj37X3v9NNfq9LpYKRzKPRHmBdtr
            dFrG7Sjk8uqH2Kh4vagNKIsQngO2pWc1dOBhBNKa6fLOiEMk+rt3A5d8V9+w8jY9
            Uhl9TB1lsNQyfCkdqn/1TWzLvemdE0jhAjTZttT8m4l5fJG8xkO59OtpjZkmvNls
            bF4NLO4KGQKBgQDHzefjAPbw/Us3gKCKJTraUXYPt+P5rLaOJdRjm58w6PrFkMUG
            KOhO8rF8rRCHvVGA+Shtndjtg9OFplKJX4/IH5SDtbjICW1yqiXY3uGIn0f2pain
            +gKoKWd4u32cWd17AdQ+TKTwrKkpqS/EksnscdUZ6ByGEUteGm+5qXVXKQKBgQCR
            JiYQs0JpGN0XlYBq9WTyqXFXBs5d5WzdRLlk3JsTHcitFnTsttV6JcGdrXoADsUG
            hbVe3+bOXgZZlTMbIUVUmuLqofFQ2/K2p8rMPz+PFRTUyikKWRD2v/bwH6v3fLpY
            N2pNn/6mt1JUw8mLEiD8UbPzpEKvveMBZfNIMny3oQKBgDyAqol9M0XMlf240Hxv
            s9YXS/jre296bK2TeqfaFfNgKOWCVZ3JiFxFGEk4Syr0Oxz1YNjpmf0q6db0CFTl
            Ujsh7U0A1m9E9DpB96B1xqVcwqmEdo/93aZghIt8hyGi5TJvCQ+E9LSgOC/4llfr
            KLjZoLDFC/c0ErFgzwAD/Q9H
            -----END PRIVATE KEY-----
            """;

    public static void main(String[] args) {

        JKSGenerator jksGenerator = new JKSGenerator(CA_CERT, CLIENT_CERT, CLIENT_KEY);
        var caJKSPath = jksGenerator.generateCaJKS();
        System.out.println(caJKSPath);
        var caClientJKSPath = jksGenerator.generateClientJKS();
        System.out.println(caClientJKSPath);
    }
}
