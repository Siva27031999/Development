    /* function for launching the exception URL */
    function launchURL() {
        const type = document.querySelector('input[name="expUIOptions"]:checked').value;
        const region = document.getElementById('regionSelect').value;
        const environment = document.getElementById('toggleEnvironment').textContent;
        const user = document.getElementById('userInput').value;
        const cluster = document.getElementById('clusterInput').value;
        const exceptionType = document.getElementById('exceptionTypeSelect').value;
        let isValid = true;
        if (!user) {
            document.getElementById('userInput').style.borderColor = 'red';
            isValid = false;
        } else {
            document.getElementById('userInput').style.borderColor = '';
        }
        if (!cluster) {
            document.getElementById('clusterInput').style.borderColor = 'red';
            isValid = false;
        } else {
            document.getElementById('clusterInput').style.borderColor = '';
        }
        if(!isValid) {
            return;
        }
        let url = '/stp-api/region-host/fetch';
        let body = new URLSearchParams({
            region: region + environment,
            type: type,
            user: user,
            cluster: cluster,
            exceptionType: exceptionType
        });

        fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: body
        })
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP error! Status: ${response.status}`);
            }
            return response.text();
        })
        .then(data => {
            if (data) {
                window.open(data, "_blank");
            }
        })
        .catch(error => {
            console.error('Error fetching the URL:', error);
        });
    }
    /* function for sending mock response */
    function sendMockResponse() {
        const region = document.getElementById('mockRegionSelect').value;
        const referenceNumber = document.getElementById('mockTransactionRef').value;
        const mockType = document.querySelector('input[name="mockType"]:checked');
        const flexType = document.getElementById('flexTypeSelect').value;
        const tsType = document.getElementById('tsTypeSelect').value;
        const discCount = document.getElementById('mockTsDiscCount').value;
        const cfxType = document.querySelector('input[name="cfxType"]:checked');
        const dealRate = document.getElementById('mockCfxDealRate').value;
        const cfxAmtCalc = document.querySelector('input[name="cfxAmtCalc"]:checked');
        let isValid = true;
        if (!referenceNumber) {
            document.getElementById('mockTransactionRef').style.borderColor = 'red';
            isValid = false;
        } else {
            document.getElementById('mockTransactionRef').style.borderColor = '';
        }
        if (!mockType) {
            document.querySelectorAll('input[name="mockType"]').forEach(function(element) {
                element.style.borderColor = 'red';
            });
            isValid = false;
        } else {
            document.querySelectorAll('input[name="mockType"]').forEach(function(element) {
                element.style.borderColor = '';
            });
        }
        let url = '';
        let body = new URLSearchParams({
            region: region,
            referenceNumber: referenceNumber
        });
        if (mockType && mockType.value === 'COMPLIANCE') {
            url = '/stp-api/mock/compliance/send';
        } else if (mockType && mockType.value === 'FLEX') {
            url = '/stp-api/mock/flex/send';
            if (!flexType) {
                document.getElementById('flexTypeSelect').style.borderColor = 'red';
                isValid = false;
            } else {
                document.getElementById('flexTypeSelect').style.borderColor = '';
                body.append('responseType', flexType);
            }
        } else if (mockType && mockType.value === 'CFX') {
            url = '/stp-api/mock/cfx/send';
            if (!cfxType) {
                document.querySelectorAll('input[name="cfxType"]').forEach(function(element) {
                    element.style.borderColor = 'red';
                });
                isValid = false;
            } else {
                document.querySelectorAll('input[name="cfxType"]').forEach(function(element) {
                    element.style.borderColor = '';
                });
                body.append('responseType', cfxType.value);
            }
            if (!dealRate) {
                document.getElementById('mockCfxDealRate').style.borderColor = 'red';
                isValid = false;
            } else {
                document.getElementById('mockCfxDealRate').style.borderColor = '';
                body.append('dealRate', dealRate);
            }
            if (!cfxAmtCalc) {
                document.querySelectorAll('input[name="cfxAmtCalc"]').forEach(function(element) {
                    element.style.borderColor = 'red';
                });
                isValid = false;
            } else {
                document.querySelectorAll('input[name="cfxAmtCalc"]').forEach(function(element) {
                    element.style.borderColor = '';
                });
                body.append('buyAmtCalc', cfxAmtCalc.value);
            }
        } else if (mockType && mockType.value === 'TS') {
                url = '/stp-api/mock/ts/send';
                if (!tsType) {
                    document.getElementById('tsTypeSelect').style.borderColor = 'red';
                    isValid = false;
                } else {
                    document.getElementById('tsTypeSelect').style.borderColor = '';
                    body.append('responseType', tsType);
                    body.append('discCount', discCount);
                }
        }
        if(!isValid) {
            document.getElementById('consoleContent').textContent = "Please enter mandatory details"; /* clearing the console content */
            document.getElementById('consoleCopy').style.display = 'none'; /* hiding the copy button */
            document.getElementById('submitButton').disabled = false;
            return; // Return a resolved promise to maintain the function signature
        }
        fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: body
        })
        .then(response => response.text())
        .then(data => {
            const consoleContent = document.getElementById('consoleContent');
            consoleContent.textContent = data;
            document.getElementById('consoleCopy').style.display = ''; /* enabling the copy button */
        })
        .catch(error => {
            document.getElementById('consoleContent').textContext = data;
            document.getElementById('consoleCopy').style.display = ''; /* enabling the copy button */
        })
        .finally(() => {
            document.getElementById('submitButton').disabled = false;
        });
    }
    function copyConsoleContent(event, contentId) {
        if (event) {
            event.preventDefault();
        }
        const consoleContent = document.getElementById(contentId).textContent;
        navigator.clipboard.writeText(consoleContent);

        navigator.clipboard.writeText(consoleContent).then(() => {
            console.log('Console content copied successfully!');
        }).catch(error => {
            console.error('Failed to copy console content:', error);
        });
    }
    /* event to handle the change when click on mock tab -> ts response */
    document.getElementById('mockTypeTs').addEventListener('change', function() {
        document.getElementById('tsTypeGroup').style.display = 'flex'; /* enabling ts response type */
        document.querySelectorAll('[name="tsDiscCount"]').forEach(function(element) {
            element.style.display = 'flex'; /* enabling ts disc count */
        });
        document.getElementById('flexTypeGroup').style.display = 'none'; /* disabling flex response type */
        document.querySelectorAll('[name="cfxTypePanel"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling cfx response type */
        });
        document.querySelectorAll('[name="cfxDealRate"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling cfx deal rate */
        });
        document.querySelectorAll('[name="cfxBuyAmtCalc"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling cfx amount calc */
        });
        const regionSelect = document.getElementById('mockRegionSelect');
        regionSelect.disabled = false; /* enabling region select */
        document.getElementById('consoleContent').textContent = "Response will be printed here !!"; /* clearing the console content */
        document.getElementById('consoleCopy').style.display = 'none'; /* hiding the copy button */
    });
    /* event to handle the change when click on mock tab -> flex response */
    document.getElementById('mockTypeFlex').addEventListener('change', function() {
        document.getElementById('flexTypeGroup').style.display = 'flex'; /* enabling flex response type */
        document.getElementById('tsTypeGroup').style.display = 'none'; /* disabling ts response type */
        document.querySelectorAll('[name="tsDiscCount"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling ts disc count */
        });
        document.querySelectorAll('[name="cfxTypePanel"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling cfx response type */
        });
        document.querySelectorAll('[name="cfxDealRate"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling cfx deal rate */
        });
        document.querySelectorAll('[name="cfxBuyAmtCalc"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling cfx amount calc */
        });
        const regionSelect = document.getElementById('mockRegionSelect');
        regionSelect.disabled = false; /* enabling region select */
        document.getElementById('consoleContent').textContent = "Response will be printed here !!"; /* clearing the console content */
        document.getElementById('consoleCopy').style.display = 'none'; /* hiding the copy button */
    });
    /* event to handle the change when click on mock tab -> compliance response */
    document.getElementById('mockTypeCompliance').addEventListener('change', function() {
        document.getElementById('flexTypeGroup').style.display = 'none'; /* disabling flex response type */
        document.getElementById('tsTypeGroup').style.display = 'none'; /* disabling ts response type */
        document.querySelectorAll('[name="cfxTypePanel"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling cfx response type */
        });
        document.querySelectorAll('[name="cfxDealRate"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling cfx deal rate */
        });
        document.querySelectorAll('[name="cfxBuyAmtCalc"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling cfx amount calc */
        });
        const regionSelect = document.getElementById('mockRegionSelect');
        regionSelect.disabled = false; /* enabling region select */
    });
    document.getElementById('tsTypeSelect').addEventListener('change', function () {
        const discCountField = document.getElementById('mockTsDiscCount'); // Assuming this is the ID of the Disc Count field
        if (this.value === 'NODISC' || this.value === 'ST') {
            discCountField.disabled = true; // Make the field non-editable
            discCountField.value = 0; // Set the default value to 0
        } else {
            discCountField.disabled = false; // Enable the field for editing
        }
    });
    document.addEventListener('DOMContentLoaded', function () {
        const tsTypeSelect = document.getElementById('tsTypeSelect');
        const discCountField = document.getElementById('mockTsDiscCount');

        // Initial check on page load
        if (tsTypeSelect.value === 'NODISC') {
            discCountField.disabled = true;
            discCountField.value = 0;
        } else {
            discCountField.disabled = false;
        }

        // Event listener for dropdown changes
        tsTypeSelect.addEventListener('change', function () {
            if (this.value === 'NODISC' || this.value === 'ST') {
                discCountField.disabled = true;
                discCountField.value = 0;
            } else {
                discCountField.disabled = false;
            }
        });
    });
    /* event to handle the change when click on mock tab -> cfx response */
    document.getElementById('mockTypeCfx').addEventListener('change', function() {
        document.querySelectorAll('[name="cfxTypePanel"]').forEach(function(element) {
            element.style.display = 'flex'; /* enabling cfx response type */
        });
        document.querySelectorAll('[name="cfxDealRate"]').forEach(function(element) {
            element.style.display = 'flex'; /* enabling cfx deal rate */
        });
        document.querySelectorAll('[name="cfxBuyAmtCalc"]').forEach(function(element) {
            element.style.display = 'flex'; /* enabling cfx amount calc */
        });
        document.querySelectorAll('[name="tsDiscCount"]').forEach(function(element) {
            element.style.display = 'none'; /* disabling ts disc count */
        });
        document.getElementById('flexTypeGroup').style.display = 'none'; /* disabling flex response type */
        document.getElementById('tsTypeGroup').style.display = 'none'; /* disabling ts response type */
        const regionSelect = document.getElementById('mockRegionSelect');
        regionSelect.value = 'INDIA'; /* setting default region as INDIA */
        regionSelect.disabled = true; /* disabling region select */
    });
    /* function for sending upload response */
    function uploadFile() {
        const region = document.getElementById('uploadRegionSelect').value;
        const environment = document.getElementById('toggleEnvironment').textContent;
        const msgType = document.getElementById('uploadType').value;
        const stpMode = document.getElementById('stpSwitch').checked;
        const stpFlag = stpMode ? 'Y' : 'N';
        const fileInput = document.getElementById('fileInput');
        const product = document.getElementById('uploadProduct').value;
        const workunitId = document.getElementById('uploadWorkunitID');
        const solutionType = document.getElementById('uploadSolutionType');
        let formData = new FormData();
        if (!fileInput || !fileInput.files || fileInput.files.length === 0) {
            document.getElementById('consoleContent1').textContent = "Please upload the file to continue.."; /* clearing the console content */
            document.getElementById('consoleCopy1').style.display = 'none'; /* hiding the copy button */
            document.getElementById('uploadButton').disabled = false;
            return;
        }
        formData.append('region', region);
        formData.append('environment', environment);
        formData.append('file', fileInput.files[0]);
        let isValid = true;
        let url = '';
        if (msgType === 'CITIDIRECT') {
            url = '/stp-api/upload/citidirect/trims';
            formData.append('stpFlag', stpFlag);
        } else if (msgType === 'OCR') {
            url = '/stp-api/upload/ocr/trims';
            formData.append('product', product);
            if (!workunitId || !workunitId.value) {
                document.getElementById('uploadWorkunitID').style.borderColor = 'red';
                isValid = false;
            } else {
                document.getElementById('uploadWorkunitID').style.borderColor = '';
                formData.append('workunitID', workunitId.value);
            }
            if (!solutionType || !solutionType.value) {
                document.getElementById('uploadSolutionType').style.borderColor = 'red';
                isValid = false;
            } else {
                document.getElementById('uploadSolutionType').style.borderColor = '';
                formData.append('solutionType', solutionType.value);
            }
        }
        if(!isValid) {
            document.getElementById('consoleContent1').textContent = "Please enter mandatory details"; /* clearing the console content */
            document.getElementById('consoleCopy1').style.display = 'none'; /* hiding the copy button */
            document.getElementById('uploadButton').disabled = false;
            return; // Return a resolved promise to maintain the function signature
        }
        fetch(url, {
            method: 'POST',
            body: formData
        })
        .then(response => response.text())
        .then(data => {
            const consoleContent = document.getElementById('consoleContent1');
            consoleContent.textContent = data;
            document.getElementById('consoleCopy1').style.display = ''; /* enabling the copy button */
        })
        .catch(error => {
            document.getElementById('consoleContent1').textContext = data;
            document.getElementById('consoleCopy1').style.display = ''; /* enabling the copy button */
        })
        .finally(() => {
            document.getElementById('uploadButton').disabled = false;
        });
    }
    /* event to handle the change when click on mock tab */
    document.getElementById('mock-tab').addEventListener('click', function() {
        document.getElementById('consoleContent').textContent = "Response will be printed here"; /* clearing the console content */
        document.getElementById('consoleCopy').style.display = 'none'; /* hiding the copy button */
    });
    document.getElementById('launch-tab').addEventListener('click', resetMockTypeTs);
    document.getElementById('mock-tab').addEventListener('click', resetMockTypeTs);
    document.getElementById('upload-tab').addEventListener('click', resetMockTypeTs);

    function resetMockTypeTs() {
        // Uncheck the radio button
        const mockTypeTs = document.getElementById('mockTypeTs');
        if (mockTypeTs) {
            mockTypeTs.checked = false;
        }

        // Hide corresponding fields
        const tsTypeGroup = document.getElementById('tsTypeGroup');
        const mockTsDiscCount = document.getElementById('mockTsDiscCount');
        if (tsTypeGroup) {
            tsTypeGroup.style.display = 'none';
        }
    }
    /* event to handle the change when click on mock tab -> send */
    document.getElementById('submitButton').addEventListener('click', function() {
        const submitButton = document.getElementById('submitButton');
        submitButton.disabled = true; // Disable the submit button

        document.getElementById('consoleContent').textContent = "Waiting for response...";
        document.getElementById('consoleCopy').style.display = 'none'; // hiding the copy button

        sendMockResponse()
            .then(() => {
                // Handle successful response if needed
            })
            .catch(() => {
                // Handle error if needed
            })
            .finally(() => {
                document.getElementById('submitButton').disabled = false;
                //submitButton.disabled = false; // Enable the submit button once the response is back
            });
    });
    /* event to handle the change when click on upload tab -> upload */
    document.getElementById('uploadButton').addEventListener('click', function() {
        const uploadButton = document.getElementById('uploadButton');
        uploadButton.disabled = true; // Disable the upload button

        document.getElementById('consoleContent1').textContent = "Waiting for response...";
        document.getElementById('consoleCopy1').style.display = 'none'; // hiding the copy button

        uploadFile()
            .catch(() => {
                // Handle error if needed
            })
            .finally(() => {
                document.getElementById('uploadButton').disabled = false;
                //uploadButton.disabled = false; // Enable the upload button once the response is back
            });
    });
    /* event to handle the change when click on upload tab */
    document.getElementById('upload-tab').addEventListener('click', function() {
        document.querySelectorAll('input[name="mockType"]').forEach(radio => {
            radio.checked = false; /* unchecking all the radio buttons */
        });
        const uploadType = document.getElementById('uploadType').value;

        if (uploadType !== 'OCR') {
            document.querySelectorAll('[name="uploadProduct"]').forEach(function(element) {
                element.style.display = 'none'; /* disabling product */
            });
            document.querySelectorAll('[name="uploadWorkunitID"]').forEach(function(element) {
                element.style.display = 'none'; /* disabling workunit id */
            });
            document.querySelectorAll('[name="uploadSolutionType"]').forEach(function(element) {
                element.style.display = 'none'; /* disabling solution type */
            });
            document.querySelector('.switch-container').style.display = 'flex';
        }
        const stpSwitch = document.getElementById("stpSwitch");
        const switchStatus = document.getElementById("switchStatus");
        //stpSwitch.checked = true;
        //switchStatus.textContent = "STP";
        //stpSwitch.disabled = false;
        resetFileInput();
        document.getElementById('consoleContent1').textContent = "Response will be printed here"; /* clearing the console content */
        document.getElementById('consoleCopy1').style.display = 'none'; /* hiding the copy button */
    });
    document.getElementById('uploadType').addEventListener('change', function() {
        const stpSwitch = document.getElementById("stpSwitch");
        const switchStatus = document.getElementById("switchStatus");
        if (this.value === 'CITIDIRECT') {
            stpSwitch.disabled = false;
            document.querySelector('.switch-container').style.display = 'flex';
            document.querySelectorAll('[name="uploadProduct"]').forEach(function(element) {
                element.style.display = 'none'; /* disabling product */
            });
            document.querySelectorAll('[name="uploadWorkunitID"]').forEach(function(element) {
                element.style.display = 'none'; /* disabling workunit id */
            });
            document.querySelectorAll('[name="uploadSolutionType"]').forEach(function(element) {
                element.style.display = 'none'; /* disabling solution type */
            });
        } else if (this.value === 'OCR') {
        document.querySelector('.switch-container').style.display = 'none';
            /*stpSwitch.checked = true;
            switchStatus.textContent = "STP";
            stpSwitch.disabled = true;*/
            document.querySelectorAll('[name="uploadProduct"]').forEach(function(element) {
                element.style.display = 'flex'; /* enabling product */
            });
            document.querySelectorAll('[name="uploadWorkunitID"]').forEach(function(element) {
                element.style.display = 'flex'; /* enabling workunit id */
            });
            document.querySelectorAll('[name="uploadSolutionType"]').forEach(function(element) {
                element.style.display = 'flex'; /* enabling solution type */
            });
        }
    });
    document.getElementById('exceptionOfficerOption').addEventListener('change', function() {
        document.getElementById('exceptionTypeGroup').style.display = 'flex';
    });
    document.getElementById('billsOption').addEventListener('change', function() {
        document.getElementById('exceptionTypeGroup').style.display = 'none';
    });
    document.getElementById('openAccountOption').addEventListener('change', function() {
        document.getElementById('exceptionTypeGroup').style.display = 'none';
    });
    document.querySelectorAll('.nav-item.dropdown > .nav-link-opt').forEach(link => {
        link.addEventListener('click', function (e) {
            e.preventDefault();
            const dropdownMenu = this.nextElementSibling;
            if (dropdownMenu.classList.contains('show')) {
                dropdownMenu.classList.remove('show');
            } else {
                document.querySelectorAll('.dropdown-menu').forEach(menu => menu.classList.remove('show'));
                dropdownMenu.classList.add('show');
            }
        });
    });
    document.querySelectorAll('.dropdown-item').forEach(item => {
        item.addEventListener('mouseover', function () {
            const parentMenu = this.closest('.dropdown-menu');
            parentMenu.querySelectorAll('.dropdown-menu').forEach(menu => menu.classList.remove('show'));
            const submenu = this.nextElementSibling;
            if (submenu && submenu.classList.contains('dropdown-menu')) {
                submenu.classList.add('show');
            }
            const parentDropdown = this.closest('.nav-item.dropdown');
            if (parentDropdown) {
                parentDropdown.classList.add('parent-active');
            }
            const parentItem = this.closest('.dropdown').querySelector('.dropdown-item');
            if (parentItem) {
                parentItem.classList.add('parent-active');
            }
        });
        item.addEventListener('mouseout', function () {
            const parentDropdown = this.closest('.nav-item.dropdown');
            if (parentDropdown) {
                parentDropdown.classList.remove('parent-active');
            }
            const parentItem = this.closest('.dropdown').querySelector('.dropdown-item');
            if (parentItem) {
                parentItem.classList.remove('parent-active');
            }
        });
    });
    document.querySelectorAll('.dropdown-menu a').forEach(link => {
        link.addEventListener('click', function () {
            if (this.hasAttribute('href') && this.getAttribute('href') !== 'javascript:void(0);') {
                document.querySelectorAll('.dropdown-menu').forEach(menu => menu.classList.remove('show'));
                document.querySelectorAll('.nav-item.dropdown').forEach(item => item.classList.remove('parent-active'));
                document.querySelectorAll('.dropdown-item').forEach(item => item.classList.remove('parent-active'));
            }
        });
    });
    document.addEventListener('mouseover', function (e) {
        if (!e.target.closest('.dropdown-menu') && !e.target.closest('.nav-item.dropdown')) {
            document.querySelectorAll('.dropdown-menu').forEach(menu => menu.classList.remove('show'));
            document.querySelectorAll('.nav-item.dropdown').forEach(item => item.classList.remove('parent-active'));
            document.querySelectorAll('.dropdown-item').forEach(item => item.classList.remove('parent-active'));
        }
    });
    document.querySelectorAll('input[name="expUIOptions"]').forEach(option => {
        option.addEventListener('change', function() {
            const regionSelect = document.getElementById('regionSelect');
            const optionsToRemove = ['ASIA', 'NAM'];
            const selectedRegion = regionSelect.value;
            const allOptions = [
                { value: 'ASIA', text: 'ASIA' },
                { value: 'EMEA', text: 'EMEA' },
                { value: 'NAM', text: 'NAM' },
                { value: 'INDIA', text: 'INDIA' }
            ];
            regionSelect.innerHTML = '';
            allOptions.forEach(option => {
                const newOption = document.createElement('option');
                newOption.value = option.value;
                newOption.text = option.text;
                regionSelect.add(newOption);
            });
            if (this.id === 'openAccountOption') {
                for (let i = 0; i < regionSelect.options.length; i++) {
                    if (optionsToRemove.includes(regionSelect.options[i].value)) {
                        regionSelect.remove(i);
                        i--; // Adjust index after removal
                    }
                }
            }
            if (Array.from(regionSelect.options).some(option => option.value === selectedRegion)) {
                regionSelect.value = selectedRegion;
            } else {
                regionSelect.selectedIndex = 0;
            }
        });
    });
    document.getElementById('regionForm').addEventListener('keydown', function(event) {
        if (event.key === 'Enter') {
            event.preventDefault(); // Prevent the default form submission
            document.querySelector('#launch button.btn-custom').click();
        }
    });
    document.getElementById('mockForm').addEventListener('keydown', function(event) {
        if (event.key === 'Enter') {
            event.preventDefault(); // Prevent the default form submission
            document.querySelector('#mock button#submitButton').click();
        }
    });
    document.getElementById('uploadForm').addEventListener('keydown', function(event) {
        if (event.key === 'Enter') {
            event.preventDefault(); // Prevent the default form submission
            document.querySelector('#upload button#uploadButton').click();
        }
    });
    document.getElementById('mockCfxDealRate').addEventListener('input', function (e) {
        const value = e.target.value;
        const regex = /^\d{0,2}(\.\d{0,3})?$/;
        if (!regex.test(value)) {
            e.target.value = value.slice(0, -1);
        }
    });

   const fileInput = document.getElementById("fileInput");
    const fileUploadArea = document.getElementById("fileUploadArea");
    const filePreview = document.getElementById("filePreview");

    // Display file name on selection
    fileInput.addEventListener("change", () => {
        if (fileInput.files.length > 0) {
            filePreview.textContent = `Selected file: ${fileInput.files[0].name}`;
            filePreview.style.display = "block";
        }
    });

    // Drag-and-drop functionality
    fileUploadArea.addEventListener("dragover", (e) => {
        e.preventDefault();
        fileUploadArea.classList.add("dragover");
    });

    fileUploadArea.addEventListener("dragleave", () => {
        fileUploadArea.classList.remove("dragover");
    });

    fileUploadArea.addEventListener("drop", (e) => {
        e.preventDefault();
        fileUploadArea.classList.remove("dragover");

        if (e.dataTransfer.files.length > 0) {
            fileInput.files = e.dataTransfer.files;
            filePreview.textContent = `Selected file: ${e.dataTransfer.files[0].name}`;
            filePreview.style.display = "block";
        }
    });

    const stpSwitch = document.getElementById("stpSwitch");
    const switchStatus = document.getElementById("switchStatus");

    // Update the label based on switch status
    stpSwitch.addEventListener("change", () => {
        switchStatus.textContent = stpSwitch.checked ? "STP" : "NSTP";
    });
    // Function to reset the file input
    function resetFileInput() {
        fileInput.value = "";
        filePreview.style.display = "none";
        filePreview.textContent = "";
    }
function sendJiraNoToBackend() {
    const jiraNo = document.getElementById('jira-id').value;
    if (jiraNo.trim() !== '') {
        const url = `/stp-api/api/jira/fetch?issueId=${jiraNo.trim()}`;
        fetch(url, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json',
            },
        })
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            console.log('Response from backend:', data);

            // Update UI fields with the JiraResponse data
            document.getElementById('jira-summary').textContent = data.fields?.summary || 'N/A';
            document.getElementById('jira-description').textContent = data.fields?.description || 'N/A';
            document.getElementById('jira-assignee').textContent = data.fields?.assignee?.displayName || 'N/A';
            document.getElementById('jiraReporter').textContent = data.fields?.reporter?.displayName || 'N/A';
            document.getElementById('jiraStatus').textContent = data.fields?.status?.name || 'N/A';
            document.getElementById('jiraEpicLink').textContent = data.fields?.epicLink || 'N/A';
            document.getElementById('jiraProgramIncrement').textContent = data.fields?.programIncrement || 'N/A';

            // Add more fields as needed
        })
        .catch(error => {
            console.error('Error:', error);
            alert('Failed to fetch Jira details. Please try again.');
        });
    } else {
        alert('Please enter a valid Jira number.');
    }
}

function toggleEnvValue(element) {
    const newValue = element.textContent === 'UAT1' ? 'UAT2' : 'UAT1';

    // Update all toggle elements with the new value
    document.querySelectorAll('.toogle-environment').forEach(toggle => {
        toggle.textContent = newValue;
    });

    // Save the updated value to localStorage
    localStorage.setItem('globalToggleValue', newValue);
}

// Restore the toggle value when the page is loaded
document.addEventListener('DOMContentLoaded', function () {
    const savedValue = localStorage.getItem('globalToggleValue') || 'UAT1'; // Default to 'UAT1'
    document.querySelectorAll('.toogle-environment').forEach(toggle => {
        toggle.textContent = savedValue;
    });
});

function updateRegionValue(element) {
    const newValue = element.value;
    // Update all region dropdowns with the new value
    document.querySelectorAll('.region-select').forEach(dropdown => {
        dropdown.value = newValue;
    });
    // Save the updated value to localStorage
    localStorage.setItem('globalRegionValue', newValue);
}

// Restore the region value when the page is loaded
document.addEventListener('DOMContentLoaded', function () {
    const savedValue = localStorage.getItem('globalRegionValue') || 'ASIA'; // Default to 'ASIAUAT1'
    document.querySelectorAll('.region-select').forEach(dropdown => {
        dropdown.value = savedValue;
    });
});

function updateStatus(elementId, url) {
    const textElement = document.getElementById(elementId);
    fetch(url)
        .then(response => {
            if (response.status !== 200) {
                return 'Portal service is currently down';
            }
            return response.text();
        })
        .then(data => {
            textElement.textContent = data;
        })
        .catch(error => {
            textElement.textContent = 'Portal service is currently down';
        });
}

function updateAllStatuses() {
    updateStatus("statusText1", "/stp-api/global-service-status/asia/service");
    updateStatus("statusText2", "/stp-api/global-service-status/emea/service");
    updateStatus("statusText3", "/stp-api/global-service-status/india/service");
    updateStatus("statusText4", "/stp-api/global-service-status/nam/service");
}

setInterval(updateAllStatuses, 60000); // Updates every 15 seconds
updateAllStatuses(); // Initial call to start the updates immediately